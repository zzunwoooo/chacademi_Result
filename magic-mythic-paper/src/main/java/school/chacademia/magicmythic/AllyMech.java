package school.chacademia.magicmythic;

import java.util.*;
import org.bukkit.Material;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * chaally{type=...} @self - support on the caster and nearby players.
 *  quench           - put out the caster's fire
 *  cleanse r=5      - remove poison, weakness, slowness from caster and allies
 *  share r=6 max=3  - pool current health of caster + allies, split by max health (no health is created)
 *  potion r=5 ticks=200 - spend the potion in hand; its effects go to caster and allies for `ticks`
 *  heal amount=2        - heal the caster only (minor_heal)
 *  buff r=6 effect=..   - effects on caster and allies (constellation_blessing)
 *  feed r=8             - fill the hunger of allies around, never the caster (sharing_table)
 *  comfort range=15 amount=10 ticks=100 - temporary hearts on the ally looked at; refreshes, never stacks (comforting_light)
 *  dawn r=8 ticks=400   - remove harmful effects from caster + allies, then block new harmful ones (dawn_ritual)
 *  oath range=10 r=10 ratio=0.3 ticks=200 - the ally looked at: part of every hit goes to the caster (guardian_oath)
 */
final class AllyMech extends Mech {
    private static final List<PotionEffectType> CLEANSE = List.of(PotionEffectType.POISON, PotionEffectType.WEAKNESS, PotionEffectType.SLOWNESS);

    AllyMech(MagicMythicPlugin plugin, Params p) { super(plugin, p); }

    @Override boolean run(Ctx c) {
        LivingEntity caster = c.caster();
        String type = p.s("type", "cleanse").toLowerCase(Locale.ROOT);
        var targets = plugin.targets();
        switch (type) {
            case "quench" -> {
                var st = plugin.status();
                boolean burning = st.has(caster, school.magiccodex.protocol.StatusProtocol.Status.BURN);
                if (caster.getFireTicks() <= 0 && !burning) return false;
                caster.setFireTicks(0);
                if (burning) st.clear(caster, school.magiccodex.protocol.StatusProtocol.Status.BURN); // 화상도 꺼짐 (마지막 추가 피해 없음)
                return true;
            }
            case "cleanse" -> {
                boolean any = false;
                var st = plugin.status();
                var statuses = new school.magiccodex.protocol.StatusProtocol.Status[]{school.magiccodex.protocol.StatusProtocol.Status.POISON,
                    school.magiccodex.protocol.StatusProtocol.Status.PHYSICAL_WEAKNESS, school.magiccodex.protocol.StatusProtocol.Status.SLOW};
                for (LivingEntity e : group(caster, p.d("r", 5), 32)) {
                    for (var t : CLEANSE) if (e.hasPotionEffect(t)) { e.removePotionEffect(t); any = true; DiscoveryHooks.cleansed(caster, p.s("spell", ""), t.getKey().getKey()); }
                    // Same three as status effects (독·나약함·둔화).
                    for (var s : statuses) if (st.has(e, s)) { st.clear(e, s); any = true; }
                }
                return any;
            }
            case "share" -> {
                var group = group(caster, p.d("r", 6), Math.max(1, p.i("max", 3)) + 1);
                if (group.size() < 2) return false;
                double[] cur = new double[group.size()], max = new double[group.size()];
                for (int i = 0; i < group.size(); i++) { cur[i] = group.get(i).getHealth(); max[i] = Targets.maxHealth(group.get(i)); }
                double[] next = Geometry.share(cur, max);
                for (int i = 0; i < group.size(); i++) group.get(i).setHealth(Math.clamp(next[i], 0.5, max[i]));
                return true;
            }
            case "potion" -> {
                if (!(caster instanceof Player player)) return false;
                ItemStack hand = player.getInventory().getItemInMainHand();
                if (hand.getType() != Material.POTION && hand.getType() != Material.SPLASH_POTION && hand.getType() != Material.LINGERING_POTION) return false;
                if (!(hand.getItemMeta() instanceof PotionMeta meta)) return false;
                var effects = new ArrayList<PotionEffect>();
                if (meta.getBasePotionType() != null) effects.addAll(meta.getBasePotionType().getPotionEffects());
                effects.addAll(meta.getCustomEffects());
                if (effects.isEmpty()) return false;
                int ticks = Math.clamp(p.i("ticks", 200), 1, 20 * 120);
                hand.setAmount(hand.getAmount() - 1);
                if (hand.getType() == Material.POTION) {
                    var leftover = player.getInventory().addItem(new ItemStack(Material.GLASS_BOTTLE));
                    leftover.values().forEach(i -> player.getWorld().dropItemNaturally(player.getLocation(), i));
                }
                for (LivingEntity e : group(caster, p.d("r", 5), 32))
                    for (PotionEffect fx : effects) {
                        // Only known beneficial types are support; unknown/neutral effects fail closed for PvP.
                        boolean beneficial=SupportPolicy.beneficial(fx.getType().getKey().getKey());
                        if(!plugin.protection().pair(caster,e.getLocation()))continue;
                        if(!SupportPolicy.permits(beneficial,e.equals(caster),!beneficial&&!e.equals(caster)&&targets.hurt(caster,e,0)))continue;
                        int d = fx.getType().isInstant() ? 1 : ticks;
                        e.addPotionEffect(new PotionEffect(fx.getType(), d, fx.getAmplifier()));
                    }
                return true;
            }
            case "heal" -> {
                if (!plugin.protection().pair(caster, caster.getLocation())) return false;
                return Targets.heal(caster, caster, p.d("amount", 2)) > 0;
            }
            case "buff" -> {
                var effects = p.effects("effect");
                if (effects.isEmpty()) return false;
                for (LivingEntity e : group(caster, p.d("r", 6), 64)){for (PotionEffect fx : effects) e.addPotionEffect(fx);if(p.s("spell", "").equals("constellation_blessing"))vfx(caster,"external:bless",e); }
                return true;
            }
            case "feed" -> {
                boolean any = false;
                for (Player ally : targets.alliesNear(caster, caster.getLocation(), p.d("r", 8), 64)) {
                    if (ally.equals(caster) || ally.getFoodLevel() >= 20) continue;
                    ally.setFoodLevel(20);
                    ally.setSaturation(Math.max(ally.getSaturation(), 5f));
                    vfx(caster, "external:deliver", ally);
                    any = true;
                }
                return any;
            }
            case "comfort" -> {
                Player ally = aimedAlly(caster, p.d("range", 15));
                if (ally == null) return false;
                TempAbsorb.give(plugin, ally, "comforting_light", p.d("amount", 10), Math.clamp(p.i("ticks", 100), 1, 20 * 60));
                vfx(caster, "external:arrive", ally);
                return true;
            }
            case "dawn" -> {
                int ticks = Math.clamp(p.i("ticks", 400), 1, 20 * 600);
                var guards = plugin.guards();
                for (LivingEntity e : group(caster, p.d("r", 8), 64)) {
                    for (PotionEffect fx : new ArrayList<>(e.getActivePotionEffects()))
                        if (fx.getType().getEffectCategory() == PotionEffectType.Category.HARMFUL) e.removePotionEffect(fx.getType());
                    plugin.status().clear(e); // every status effect is harmful
                    guards.add(e, new Guards.Guard(Guards.Type.IMMUNE, guards.now() + ticks, 0, 0, 0, 0, 0, 0, caster.getUniqueId(), p.s("spell", "")));
                    vfx(caster, "external:cleanse", e);
                }
                return true;
            }
            case "oath" -> {
                Player ally = aimedAlly(caster, p.d("range", 10));
                if (ally == null) return false;
                var guards = plugin.guards();
                guards.add(ally, new Guards.Guard(Guards.Type.OATH, guards.now() + Math.clamp(p.i("ticks", 200), 1, 20 * 600),
                    p.d("r", 10), Math.clamp(p.d("ratio", 0.3), 0, 1), 0, 0, 0, 0, caster.getUniqueId(), p.s("spell", "")));
                vfx(caster, "external:form", ally);
                return true;
            }
            default -> { return false; }
        }
    }

    private void vfx(LivingEntity caster, String event, Entity at) {
        String spell = p.s("spell", "");
        if (!spell.isBlank() && caster instanceof Player pl) Links.vfx(pl, spell, event, at.getLocation(), at.getUniqueId());
    }

    /** The allied player the caster looks at (blocks stop the look). */
    Player aimedAlly(LivingEntity caster, double range) {
        var eye = caster.getEyeLocation();
        var hit = caster.getWorld().rayTraceEntities(eye, eye.getDirection(), range, 0.8,
            e -> e instanceof Player && !e.equals(caster) && plugin.targets().ally(caster, e));
        if (hit == null || !(hit.getHitEntity() instanceof Player ally)) return null;
        var block = caster.getWorld().rayTraceBlocks(eye, eye.getDirection(), range, org.bukkit.FluidCollisionMode.NEVER, true);
        if (block != null && block.getHitPosition().distanceSquared(eye.toVector()) < hit.getHitPosition().distanceSquared(eye.toVector())) return null;
        return ally;
    }

    /** Caster first, then nearest allies. */
    private List<LivingEntity> group(LivingEntity caster, double r, int max) {
        var out = new ArrayList<LivingEntity>();
        if(plugin.protection().pair(caster,caster.getLocation()))out.add(caster);
        for (Player p : plugin.targets().alliesNear(caster, caster.getLocation(), r, max))
            if (!p.equals(caster) && out.size() < max) out.add(p);
        return out;
    }
}
