package school.chacademia.magicmythic;

import io.lumine.mythic.bukkit.MythicBukkit;
import java.util.*;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.*;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.util.Vector;

/** One place that decides who a spell may hurt or help, and how damage/knockback is applied. */
final class Targets {
    private final MagicMythicPlugin plugin;
    private Set<String> bossTags = Set.of();
    private List<String> bossPrefixes = List.of();
    private double bossKnock = 0.3;

    Targets(MagicMythicPlugin plugin) { this.plugin = plugin; reload(); }

    void reload() {
        var c = plugin.getConfig();
        bossTags = Set.copyOf(c.getStringList("boss-tags"));
        bossPrefixes = List.copyOf(c.getStringList("boss-mythic-prefixes"));
        bossKnock = Math.clamp(c.getDouble("boss-knockback-multiplier", 0.3), 0, 1);
    }

    /** Mythic internal name or null. */
    static String mythicType(Entity e) {
        try {
            return MythicBukkit.inst().getMobManager().getActiveMob(e.getUniqueId())
                .map(m -> m.getType().getInternalName()).orElse(null);
        } catch (RuntimeException | LinkageError ignored) { return null; }
    }

    boolean enemy(LivingEntity caster, Entity e) {
        if (!(e instanceof LivingEntity le) || e.equals(caster) || !le.isValid() || le.isDead()) return false;
        if (excluded(e)||!plugin.protection().harm(caster,le)) return false;
        // Animals, owned pets, summons and custom mobs are valid attack targets.
        // Creative/spectator players remain outside gameplay combat; WG handles region PvP.
        // C03: party members (match: teammates) never take damage, CC, element marks, knockback or pulls from each other.
        return !(e instanceof Player p) || playable(p) && !friends(caster, p);
    }

    /** C03 {@link AllyResolver}: both players and on the same side (party, or match team during a match). */
    private boolean friends(LivingEntity caster, Player p) {
        var allies = plugin.allies();
        return allies != null && caster instanceof Player cp && allies.allied(cp, p);
    }

    boolean excluded(Entity e){
        return TargetPolicy.excluded(e instanceof ArmorStand, e.hasMetadata("NPC"), e.getScoreboardTags());
    }

    boolean ally(LivingEntity caster, Entity e) {
        if (!(e instanceof Player p) || !playable(p)||excluded(e)||!plugin.protection().pair(caster,p.getLocation())) return false;
        // C03 (owner decision 7): only the caster itself and its party (match: its team) are allies, anywhere.
        return e.equals(caster) || friends(caster, p);
    }

    boolean boss(LivingEntity e) {
        if (e instanceof EnderDragon || e instanceof Wither || e instanceof Warden || e instanceof ElderGuardian) return true;
        for (String tag : e.getScoreboardTags()) if (bossTags.contains(tag)) return true;
        String mythic = mythicType(e);
        if (mythic != null) for (String prefix : bossPrefixes) if (mythic.startsWith(prefix)) return true;
        return false;
    }

    private static boolean playable(Player p) {
        return p.isValid() && !p.isDead() && (p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE);
    }

    /** Enemies around a point, nearest first. */
    List<LivingEntity> enemiesNear(LivingEntity caster, Location center, double radius, int max) {
        if (center.getWorld() == null || radius <= 0) return List.of();
        var list = new ArrayList<LivingEntity>();
        double search = radius + QuidditchCombat.searchMargin(); // 큐디치 비행 선수는 몸 상자가 커서 조금 더 넓게 찾는다
        for (Entity e : center.getWorld().getNearbyEntities(center, search, search, search)) {
            if (!enemy(caster, e)) continue;
            LivingEntity le = (LivingEntity) e;
            if (closestDistance(le, center) <= radius) list.add(le);
        }
        list.sort(Comparator.comparingDouble(le -> le.getLocation().distanceSquared(center)));
        return list.size() > max ? list.subList(0, Math.max(0, max)) : list;
    }

    List<Player> alliesNear(LivingEntity caster, Location center, double radius, int max) {
        var list = new ArrayList<Player>();
        if (center.getWorld() == null) return list;
        for (Entity entity : center.getWorld().getNearbyEntities(center,radius,radius,radius)) {
            if(!(entity instanceof Player p))continue;
            if (ally(caster, p) && p.getLocation().distanceSquared(center) <= radius * radius) list.add(p);
        }
        list.sort(Comparator.comparingDouble(p -> p.getLocation().distanceSquared(center)));
        return list.size() > max ? list.subList(0, Math.max(0, max)) : list;
    }

    /**
     * Distance from a point to the entity's bounding box, so large mobs are hit at their edge.
     * 큐디치 경기 중 비행하는 선수는 키운 상자 ({@link QuidditchCombat#hitBox}, #33).
     */
    static double closestDistance(Entity e, Location p) {
        var box = QuidditchCombat.hitBox(e);
        double x = Math.clamp(p.getX(), box.getMinX(), box.getMaxX());
        double y = Math.clamp(p.getY(), box.getMinY(), box.getMaxY());
        double z = Math.clamp(p.getZ(), box.getMinZ(), box.getMaxZ());
        return Math.sqrt(Math.pow(p.getX() - x, 2) + Math.pow(p.getY() - y, 2) + Math.pow(p.getZ() - z, 2));
    }

    boolean hurt(LivingEntity caster, LivingEntity target, double amount) { return hurt(caster, target, amount, "", ""); }

    /** Spell damage with its element (for {@link MagicDamageEvent}: artifact bonuses). */
    boolean hurt(LivingEntity caster, LivingEntity target, double amount, String element) { return hurt(caster, target, amount, element, ""); }

    boolean hurt(LivingEntity caster, LivingEntity target, double amount, String element, String reaction) {
        if (!enemy(caster,target)) return false;
        amount = Math.max(0, amount);
        if (amount > 0 && plugin.status() != null) amount *= plugin.status().spellMultiplier(caster); // 약화
        amount *= pvpScale(caster, target); // 전투 스펙 2026-10-26: 플레이어→플레이어는 PvP 전용 피해
        if (amount > 0 && caster instanceof Player && MagicDamageEvent.listened()) {
            var mark = plugin.reactions() == null ? null : plugin.reactions().markOf(target);
            var e = new MagicDamageEvent(caster, target, element == null ? "" : element, reaction == null ? "" : reaction,
                    mark == null ? "" : mark.element().id, mark == null ? null : mark.caster(), amount, SpellTrace.current().spell());
            Bukkit.getPluginManager().callEvent(e);
            amount = e.getDamage();
        }
        return plugin.protection().damage(caster,target,amount);
    }

    /**
     * 전투 스펙 2026-10-26: PvP 피해 = k × (40 + 지능) (몹 상대 피해 ÷ 이 배율). 시전이 넘긴 pvp_scale (SpellTrace), 없으면 config pvp.fallback-scale.
     * 몹이 쓰거나 몹이 맞는 마법은 1 (그대로).
     */
    static double pvpScale(LivingEntity caster, Entity target) {
        if (!(caster instanceof Player) || !(target instanceof Player) || caster.equals(target) || !MagicArmor.current.enabled()) return 1;
        return MagicArmor.pvpScale(MagicArmor.current, SpellTrace.current().pvpScale());
    }

    /** 처형 (크로스 애로우): 보호막·방어·저항·약화·PvP 배율 모두 무시하고 쓰러뜨린다. 취소된 피해는 그대로 실패. */
    boolean execute(LivingEntity caster, LivingEntity target, String element) {
        if (!enemy(caster, target)) return false;
        return plugin.protection().execute(caster, target, target.getHealth() + target.getAbsorptionAmount() + 1);
    }

    /** Spell heal on a player (zone heal, rainbow). Fires {@link MagicHealEvent} for artifact bonuses. */
    void heal(LivingEntity caster, Player ally, double amount, String reaction) {
        if (amount <= 0) return;
        if (MagicHealEvent.listened()) {
            var e = new MagicHealEvent(caster, ally, reaction, amount);
            Bukkit.getPluginManager().callEvent(e);
            amount = e.getAmount();
        }
        heal(caster, ally, amount);
    }
    /** Same as hurt, but marked as a passive's extra hit so it never re-triggers a passive. */
    boolean hurtProc(LivingEntity caster, LivingEntity target, double amount) {
        if (plugin.status() != null) amount *= plugin.status().spellMultiplier(caster); // 약화
        amount *= pvpScale(caster, target); // 전투 스펙: PvP 전용 피해
        return enemy(caster,target)&&plugin.protection().procDamage(caster,target,Math.max(0,amount));
    }

    /** Undead (vanilla tag sensitive_to_smite: zombies, skeletons, phantoms, withers ...). */
    static boolean undead(Entity e) {
        try { return org.bukkit.Tag.ENTITY_TYPES_SENSITIVE_TO_SMITE.isTagged(e.getType()); }
        catch (RuntimeException | LinkageError ignored) { return e instanceof Zombie || e instanceof AbstractSkeleton || e instanceof Phantom || e instanceof Wither; }
    }

    /** Spell healing by `caster` (counted for acquisition conditions). */
    static double heal(LivingEntity caster, LivingEntity e, double amount) {
        double healed = heal(e, amount);
        DiscoveryHooks.healed(caster, e, healed);
        return healed;
    }

    /** Heal without going over max health. Returns the amount actually healed. */
    static double heal(LivingEntity e, double amount) {
        if (amount <= 0 || !e.isValid() || e.isDead()) return 0;
        if (sealed != null && sealed.test(e)) return 0; // 회복봉인

        double before = e.getHealth(), after = Math.min(maxHealth(e), before + amount);
        if (after <= before) return 0;
        e.setHealth(after);
        return after - before;
    }

    /** Set by the plugin: 회복봉인 check for the static heal helper. */
    static java.util.function.Predicate<LivingEntity> sealed;

    /**
     * Spell side effects. Slowness becomes the 둔화 status (icon, 15 % per level like vanilla, same duration);
     * other potion effects stay vanilla.
     */
    void effects(LivingEntity target, List<PotionEffect> effects) { effects(null, target, effects); }
    void effects(LivingEntity caster, LivingEntity target, List<PotionEffect> effects) {
        for (PotionEffect e : effects) {
            if (e.getType().equals(org.bukkit.potion.PotionEffectType.SLOWNESS) && plugin.status() != null) {
                plugin.status().apply(caster, target, school.magiccodex.protocol.StatusProtocol.Status.SLOW, e.getDuration(), Math.min(0.9, 0.15 * (e.getAmplifier() + 1)));
                continue;
            }
            target.addPotionEffect(e);
        }
    }

    /**
     * Common on-hit extras of the cha* mechanics: `ignite=<ticks>` = 화상 (no vanilla fire, owner 2026-10-19),
     * `status=<id>:<ticks>[:<power>],...` = any status effect (e.g. status=stun:20).
     */
    void sideEffects(LivingEntity caster, LivingEntity target, Params p) {
        var st = plugin.status();
        if (st == null || target == null || !target.isValid() || target.isDead()) return;
        int ignite = p.i("ignite", 0);
        if (ignite > 0) st.apply(caster, target, school.magiccodex.protocol.StatusProtocol.Status.BURN, ignite, 0);
        String raw = p.s("status", "");
        if (raw.isBlank()) return;
        for (String part : raw.split("[,+]")) {
            String[] f = part.trim().split(":");
            var s = school.magiccodex.protocol.StatusProtocol.Status.of(f[0]);
            if (s == null) continue;
            int ticks = 60; double power = 0;
            try { if (f.length > 1) ticks = Integer.parseInt(f[1].trim()); if (f.length > 2) power = Double.parseDouble(f[2].trim()); }
            catch (NumberFormatException ignored) { continue; }
            st.apply(caster, target, s, ticks, power);
        }
    }

    /** Push away from `from` horizontally by `knock`, plus `up`. Bosses get a reduced push. */
    void knock(LivingEntity target, Location from, Vector fallbackDir, double knock, double up) {
        if (knock == 0 && up == 0) return;
        Vector dir = target.getLocation().toVector().subtract(from.toVector()).setY(0);
        if (dir.lengthSquared() < 1e-4) dir = fallbackDir.clone().setY(0);
        if (dir.lengthSquared() < 1e-4) dir = new Vector(0, 0, 0); else dir.normalize();
        double scale = boss(target) ? bossKnock : 1;
        Vector v = dir.multiply(knock * scale).setY(up * scale + (knock != 0 ? 0.1 * scale : 0));
        target.setVelocity(v);
    }

    static double maxHealth(LivingEntity e) {
        var a = e.getAttribute(Attribute.MAX_HEALTH);
        return a == null ? 20 : a.getValue();
    }

    // ---------- block protection, same pattern as MagicCodexBridge SpellUtility ----------
    /** True while canBreak fires its fake BlockBreakEvent (listeners must not treat it as a real break). */
    static boolean probing = false;
    static boolean canBreak(Player p, Block b) {
        if(!MagicMythicAccess.at(p,b.getLocation()))return false;
        BlockData before = b.getBlockData().clone();
        var event = new BlockBreakEvent(b, p);
        event.setDropItems(false);
        probing = true;
        try { Bukkit.getPluginManager().callEvent(event); } finally { probing = false; }
        return !event.isCancelled() && b.getBlockData().equals(before);
    }

    static boolean canPlace(Player p, Block b, Block against, Material item) {
        if(!MagicMythicAccess.at(p,b.getLocation()))return false;
        BlockState replaced = b.getState();
        var event = new BlockPlaceEvent(b, replaced, against, new ItemStack(item), p, true, EquipmentSlot.HAND);
        Bukkit.getPluginManager().callEvent(event);
        return !event.isCancelled() && event.canBuild();
    }
}
