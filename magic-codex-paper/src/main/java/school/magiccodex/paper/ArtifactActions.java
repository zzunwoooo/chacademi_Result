package school.magiccodex.paper;

import java.util.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Use-key artifacts (아티팩트 사용 키, default X). ArtifactBridge already checked ownership, equipment and the cooldown
 * and fires {@link ArtifactUseEvent}; here we do the effect and call setUsed(true) (only then the cooldown starts).
 * Not handled here (held, see the handoff doc): folding_spyglass (client zoom). shapeshift_mask → {@link ArtifactShapeshift}.
 */
final class ArtifactActions implements Listener {
    private final ArtifactEffects fx;
    /** rewind_hourglass: position/health/mana every 0.5 s for the last 6 s. */
    private record Moment(Location at, double health, double mana, long time) {}
    private final Map<UUID, Deque<Moment>> history = new HashMap<>();

    ArtifactActions(ArtifactEffects fx) { this.fx = fx; }

    @EventHandler public void use(ArtifactUseEvent e) {
        Player p = e.getPlayer();
        String result = switch (e.getArtifactId()) {
            case "name_tag" -> nameTag(p);
            case "pocket_crafting_table" -> { p.openWorkbench(null, true); yield ""; }
            case "pocket_anvil" -> { p.openAnvil(null, true); yield ""; }
            case "snack_box" -> { give(p, new ItemStack(Material.COOKIE)); p.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, .6f, 1.2f); yield "쿠키 1개를 꺼냈습니다."; }
            case "canteen" -> canteen(p);
            case "party_popper" -> { burst(p.getEyeLocation().add(0, 0.8, 0)); yield ""; }
            case "lucky_die" -> luckyDie(p);
            case "cheer_light_stick" -> lightStick(p);
            case "wave_flag" -> { p.swingMainHand(); fx.bubble(p, "여기!", 60); p.getWorld().playSound(p.getLocation(), Sound.ENTITY_VILLAGER_YES, .5f, 1.4f); yield ""; }
            case "signal_whistle" -> whistle(p);
            case "element_ink_bottle" -> inkBottle(p);
            case "reaction_detonator_glove" -> detonator(p);
            case "mana_battery" -> manaBattery(p);
            case "war_horn" -> warHorn(p);
            case "tactics_flag" -> tacticsFlag(p);
            case "chant_candlestick" -> { fx.run(p).chantArmed = true; p.getWorld().playSound(p.getLocation(), Sound.ITEM_FLINTANDSTEEL_USE, 1f, 1.2f); yield "다음 마법 1개는 마나를 쓰지 않습니다."; }
            case "rewind_hourglass" -> rewind(p);
            case "wish_coin" -> wishCoin(p);
            // 접이식 망원경: 새 MagicCodex 모드는 사용 키를 누르고 있는 동안 직접 확대하고 서버에 보내지 않는다 (옛 모드만 여기로 옴)
            case "folding_spyglass" -> "!접이식 망원경은 최신 MagicCodex 모드에서 사용 키를 누르고 있으면 확대됩니다.";
            case "shapeshift_mask" -> fx.shapeshift.use(p);          // 변신 가면 (ArtifactShapeshift)
            default -> null;
        };
        if (result == null) return;                       // not ours / held: ArtifactBridge shows "준비 중"
        if (result.startsWith("!")) { e.setResponse(result.substring(1)); return; } // failed: no cooldown
        e.setUsed(true);
        if (!result.isEmpty()) e.setResponse(result);
    }

    // ------------------------------------------------------------------ simple items

    private String nameTag(Player p) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand.getType().isAir()) return "!손에 아이템을 들어 주세요.";
        String line = "주인 : " + fx.plugin.names().name(p);
        hand.editMeta(meta -> {
            var lore = meta.lore() == null ? new ArrayList<Component>() : new ArrayList<>(meta.lore());
            lore.removeIf(c -> net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(c).startsWith("주인 : "));
            lore.add(Component.text(line, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
            meta.lore(lore);
        });
        p.playSound(p.getLocation(), Sound.UI_CARTOGRAPHY_TABLE_TAKE_RESULT, .7f, 1.2f);
        return "이름을 새겼습니다.";
    }

    private String canteen(Player p) {
        if (p.getFoodLevel() >= 20) return "!배가 부릅니다.";
        p.setFoodLevel(Math.min(20, p.getFoodLevel() + 2));
        p.playSound(p.getLocation(), Sound.ENTITY_GENERIC_DRINK, .7f, 1f);
        return "배고픔이 1칸 회복되었습니다.";
    }

    static void give(Player p, ItemStack item) {
        for (var left : p.getInventory().addItem(item).values()) p.getWorld().dropItemNaturally(p.getLocation(), left);
    }

    /** Small celebration burst (no firework entity: no damage, no entity cost). */
    static void burst(Location at) {
        var w = at.getWorld(); if (w == null) return;
        w.spawnParticle(Particle.FIREWORK, at, 40, .25, .25, .25, .12);
        Color[] colors = {Color.fromRGB(0xFF7EB6), Color.fromRGB(0xFFE066), Color.fromRGB(0x7FDBFF), Color.fromRGB(0xB28DFF)};
        for (Color c : colors) w.spawnParticle(Particle.DUST, at, 12, .5, .4, .5, 0, new Particle.DustOptions(c, 1.2f));
        w.playSound(at, Sound.ENTITY_FIREWORK_ROCKET_BLAST, .8f, 1.3f);
        w.playSound(at, Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, .6f, 1.4f);
    }

    private String luckyDie(Player p) {
        int n = 1 + fx.random.nextInt(6);
        fx.bubble(p, "🎲 " + n, 60);
        var text = Component.text(fx.plugin.names().name(p) + "의 주사위: " + n, n == 6 ? NamedTextColor.GOLD : NamedTextColor.YELLOW);
        for (Player o : p.getWorld().getPlayers()) if (o.getLocation().distanceSquared(p.getLocation()) <= 16 * 16) o.sendMessage(text);
        p.getWorld().playSound(p.getLocation(), Sound.BLOCK_WOOD_HIT, 1f, 1.5f);
        if (n == 6) { fx.timed(p, "lucky_die", 10_000, 0.10, 0); p.getWorld().playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, .5f, 1.8f); }
        return "";
    }

    private Player lookedPlayer(Player p, double range) {
        var hit = p.getWorld().rayTraceEntities(p.getEyeLocation(), p.getEyeLocation().getDirection(), range, 0.6, e -> e instanceof Player o && !o.equals(p));
        return hit != null && hit.getHitEntity() instanceof Player o ? o : null;
    }
    private LivingEntity lookedEntity(Player p, double range) {
        var hit = p.getWorld().rayTraceEntities(p.getEyeLocation(), p.getEyeLocation().getDirection(), range, 0.5,
                e -> e instanceof LivingEntity && !e.equals(p) && !(e instanceof ArmorStand));
        if (hit == null || !(hit.getHitEntity() instanceof LivingEntity t)) return null;
        var block = p.getWorld().rayTraceBlocks(p.getEyeLocation(), p.getEyeLocation().getDirection(), range, FluidCollisionMode.NEVER, true);
        if (block != null && block.getHitPosition().distanceSquared(p.getEyeLocation().toVector()) < hit.getHitPosition().distanceSquared(p.getEyeLocation().toVector())) return null;
        return t;
    }

    private String lightStick(Player p) {
        p.swingMainHand();
        Player target = lookedPlayer(p, 16);
        p.getWorld().spawnParticle(Particle.END_ROD, p.getEyeLocation().add(p.getLocation().getDirection().multiply(0.6)), 8, .1, .3, .1, .02);
        if (target != null) {
            for (int i = 0; i < 4; i++) Bukkit.getScheduler().runTaskLater(fx.plugin, () -> {
                if (target.isOnline()) target.getWorld().spawnParticle(Particle.HEART, target.getLocation().add(0, 2.3, 0), 2, .25, .1, .25, 0);
            }, i * 5L);
        }
        return "";
    }

    // ------------------------------------------------------------------ party

    private String whistle(Player p) {
        var mates = fx.party.onlineMates(p).stream().filter(m -> m.getWorld() == p.getWorld() && m.getLocation().distanceSquared(p.getLocation()) <= 32 * 32).toList();
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_BREEZE_WHIRL, .6f, 2f);
        if (mates.isEmpty()) return "!32블록 안에 파티원이 없습니다.";
        String name = fx.plugin.names().name(p);
        for (Player m : mates) m.playSound(m.getLocation(), Sound.BLOCK_NOTE_BLOCK_FLUTE, 1f, 2f);
        new org.bukkit.scheduler.BukkitRunnable() {
            int left = 20;
            @Override public void run() {
                if (left-- <= 0 || !p.isOnline()) { cancel(); return; }
                for (Player m : mates) {
                    if (!m.isOnline() || m.getWorld() != p.getWorld()) continue;
                    var to = p.getLocation().toVector().subtract(m.getLocation().toVector());
                    double dist = to.length();
                    double angle = Math.toDegrees(Math.atan2(-to.getX(), to.getZ())) - m.getLocation().getYaw();
                    m.sendActionBar(Component.text(arrow(angle) + " " + name + "  " + Math.round(dist) + "m", NamedTextColor.AQUA));
                }
            }
        }.runTaskTimer(fx.plugin, 0, 5);
        return "파티원에게 신호를 보냈습니다.";
    }

    static String arrow(double degrees) {
        String[] arrows = {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖"};
        double d = ((degrees % 360) + 360) % 360;
        return arrows[(int) Math.round(d / 45) % 8];
    }

    private String warHorn(Player p) {
        var group = new ArrayList<Player>();
        group.add(p);
        for (Player m : fx.party.onlineMates(p)) if (m.getWorld() == p.getWorld() && m.getLocation().distanceSquared(p.getLocation()) <= 64) group.add(m);
        p.getWorld().playSound(p.getLocation(), Sound.EVENT_RAID_HORN, 1.2f, 1.2f);
        for (Player m : group) {
            fx.timed(m, "war_horn", 10_000, 0, 10);
            ArtifactEffects.notice(m, "war_horn", "전쟁 뿔피리", "10초간 스킬 가속 +10");
        }
        return "";
    }

    private String tacticsFlag(Player p) {
        var hit = p.getWorld().rayTraceBlocks(p.getEyeLocation(), p.getEyeLocation().getDirection(), 64, FluidCollisionMode.NEVER, true);
        if (hit == null || hit.getHitBlock() == null) return "!깃발을 꽂을 곳을 바라봐 주세요 (64블록 안).";
        Location at = hit.getHitBlock().getLocation().add(0.5, 1, 0.5);
        var viewers = new ArrayList<Player>(fx.party.onlineMates(p)); viewers.add(p);
        String name = fx.plugin.names().name(p);
        var flag = at.getWorld().spawn(at.clone().add(0, 0.9, 0), ItemDisplay.class, d -> {
            d.setItemStack(new ItemStack(Material.RED_BANNER));
            d.setVisibleByDefault(false); d.setPersistent(false); d.setGlowing(true);
            d.setGlowColorOverride(Color.fromRGB(0xFFD24A));
            d.setBillboard(Display.Billboard.VERTICAL);
            d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(1.4f, 1.4f, 1.4f), new Quaternionf()));
        });
        var label = at.getWorld().spawn(at.clone().add(0, 2.2, 0), TextDisplay.class, d -> {
            d.text(Component.text("⚑ " + name, NamedTextColor.GOLD));
            d.setVisibleByDefault(false); d.setPersistent(false); d.setSeeThrough(true);
            d.setBillboard(Display.Billboard.CENTER);
        });
        for (Player v : viewers) { v.showEntity(fx.plugin, flag); v.showEntity(fx.plugin, label); }
        at.getWorld().playSound(at, Sound.ITEM_ARMOR_EQUIP_LEATHER, 1f, 1f);
        Bukkit.getScheduler().runTaskLater(fx.plugin, () -> { flag.remove(); label.remove(); }, 600);
        return "깃발을 꽂았습니다 (30초).";
    }

    // ------------------------------------------------------------------ magic

    private String inkBottle(Player p) {
        String element = fx.run(p).lastElement;
        if (element == null || element.isEmpty()) return "!먼저 원소 마법을 써 주세요.";
        LivingEntity t = lookedEntity(p, 16);
        if (t == null) return "!바라보는 적이 없습니다 (16블록 안).";
        Object ok = fx.magic().api("forceMark", new Class<?>[]{Player.class, LivingEntity.class, String.class}, p, t, element);
        if (!Boolean.TRUE.equals(ok)) return "!표식을 남길 수 없는 대상입니다.";
        p.getWorld().playSound(t.getLocation(), Sound.ITEM_INK_SAC_USE, 1f, 1f);
        return "";
    }

    private String detonator(Player p) {
        LivingEntity t = lookedEntity(p, 16);
        if (t == null) return "!바라보는 적이 없습니다 (16블록 안).";
        double damage = 4 + fx.plugin.magicPower(p) * 0.5;
        Object popped = fx.magic().api("detonateMark", new Class<?>[]{Player.class, LivingEntity.class, double.class}, p, t, damage);
        if (popped == null) return "!원소 표식이 붙은 적을 바라봐 주세요.";
        t.getWorld().spawnParticle(Particle.EXPLOSION, t.getLocation().add(0, t.getHeight() / 2, 0), 1);
        t.getWorld().playSound(t.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, .6f, 1.6f);
        return "";
    }

    private String manaBattery(Player p) {
        var snap = fx.mana.snapshot(p.getUniqueId()).orElse(null);
        if (snap == null) return "!마나 정보를 불러오는 중입니다.";
        if (snap.current() >= snap.maximum()) return "!마나가 이미 가득합니다.";
        fx.mana.setCurrent(p.getUniqueId(), snap.maximum());
        p.getWorld().playSound(p.getLocation(), Sound.BLOCK_BEACON_POWER_SELECT, .8f, 1.6f);
        return "마나를 전부 회복했습니다.";
    }

    // ------------------------------------------------------------------ rewind / wish

    void fast(Player p) {
        if (!fx.has(p, "rewind_hourglass")) return;
        var d = history.computeIfAbsent(p.getUniqueId(), k -> new ArrayDeque<>());
        long now = System.currentTimeMillis();
        if (!d.isEmpty() && now - d.peekLast().time() < 500) return; // one moment every 0.5 s
        double m = fx.mana.snapshot(p.getUniqueId()).map(s -> s.current()).orElse(-1d);
        d.addLast(new Moment(p.getLocation(), p.getHealth(), m, now));
        while (!d.isEmpty() && now - d.peekFirst().time() > 6_500) d.removeFirst();
    }

    private String rewind(Player p) {
        var d = history.get(p.getUniqueId());
        long now = System.currentTimeMillis();
        Moment pick = null;
        if (d != null) for (Moment m : d) if (now - m.time() >= 5_000) pick = m;
        if (pick == null) return "!되감을 기록이 아직 없습니다. 잠시 뒤 다시 써 주세요.";
        if (pick.at().getWorld() != p.getWorld()) return "!다른 세계로는 되감을 수 없습니다.";
        Location from = p.getLocation();
        from.getWorld().spawnParticle(Particle.REVERSE_PORTAL, from.add(0, 1, 0), 40, .4, .6, .4, .05);
        p.teleport(pick.at());
        var max = p.getAttribute(Attribute.MAX_HEALTH);
        p.setHealth(Math.max(1, Math.min(max == null ? 20 : max.getValue(), pick.health())));
        if (pick.mana() >= 0 && fx.mana.snapshot(p.getUniqueId()).isPresent()) fx.mana.setCurrent(p.getUniqueId(), pick.mana());
        p.setFallDistance(0); p.setFireTicks(0);
        p.getWorld().spawnParticle(Particle.REVERSE_PORTAL, p.getLocation().add(0, 1, 0), 60, .4, .6, .4, .08);
        p.getWorld().playSound(p.getLocation(), Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE, .8f, 1.5f);
        d.clear();
        return "5초 전으로 되감았습니다.";
    }

    private static final List<Map.Entry<PotionEffectType, String>> WISHES = List.of(
            Map.entry(PotionEffectType.SPEED, "신속"), Map.entry(PotionEffectType.HASTE, "성급함"),
            Map.entry(PotionEffectType.JUMP_BOOST, "점프 강화"), Map.entry(PotionEffectType.NIGHT_VISION, "야간 투시"),
            Map.entry(PotionEffectType.FIRE_RESISTANCE, "화염 저항"), Map.entry(PotionEffectType.WATER_BREATHING, "수중 호흡"),
            Map.entry(PotionEffectType.LUCK, "행운"), Map.entry(PotionEffectType.SLOW_FALLING, "느린 낙하"));

    private String wishCoin(Player p) {
        var wish = WISHES.get(fx.random.nextInt(WISHES.size()));
        p.addPotionEffect(new PotionEffect(wish.getKey(), 20 * 600, 0, false, true));
        p.getWorld().spawnParticle(Particle.WAX_OFF, p.getLocation().add(0, 1.2, 0), 20, .4, .5, .4, 0);
        p.getWorld().playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 1.6f);
        return "소원 동전: 10분간 " + wish.getValue();
    }

    void quit(UUID id) { history.remove(id); }
}
