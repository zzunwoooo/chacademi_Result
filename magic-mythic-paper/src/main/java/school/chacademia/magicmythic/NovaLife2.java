package school.chacademia.magicmythic;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * chanova types 375 / 376 / 380 / 385 / 387 (생활, 새 시스템 연동). Rows: tools/nova/life2.py. VFX: PC 차카데미 vfx_Claude\<Bundle>\NOTES.md.
 *  - 빗자루 (375, 380): ChacademyMotion broomSpeedBuff / broomBalanceBuff through {@link BroomLink} (mod channel chacademy:broom_buff).
 *  - 지도 (376): chacademia:map_ping to party members (magic-codex-fabric MapPingClient draws it on the travel map).
 *  - 살아나는 작물 (385, 387): ChAcademiaWildlife CropAwakening reads the harvester's Bukkit metadata
 *    {@value Crops#CHANCE} (number = chance multiplier) and calls {@value Crops#SPAWNED} (Consumer&lt;Entity&gt;) when a
 *    crop mob comes out of that player's harvest. No compile dependency either way.
 */
final class NovaLife2 {
    private NovaLife2() {}

    static final String MAP_PING = "chacademia:map_ping";

    static void register(Map<String, NovaMech.Impl> m) {
        // 375 불꽃 추진 (BroomAfterburner): on a broom only. 3 s top speed x1.2, the broom trail is hidden while the flame jet plays.
        m.put("broom_afterburner", n -> {
            Player pl = n.pl;
            double mana = n.d("mana", 80);
            int ticks = n.i("ticks", 60);
            if (!BroomLink.riding(pl)) { n.warn("빗자루를 타고 있을 때만 쓸 수 있어요"); n.refund(mana); return false; }
            if (!BroomLink.speedBuff(pl, (float) n.d("speed", 1.2), ticks, true)) {
                n.warn("빗자루 모드가 오래되어 불꽃 추진을 쓸 수 없어요"); n.refund(mana); return false;
            }
            // begin plays with the cast (t = 0); sustain from +0.1 s; end at the boost end or at once when the rider gets off
            n.later(1, () -> { if (BroomLink.riding(pl)) n.fx("broom_afterburner", pl.getLocation(), pl); });
            boolean[] ended = {false};
            Runnable end = () -> { if (!ended[0]) { ended[0] = true; n.fx("broom_afterburner_end", pl.getLocation(), pl); } };
            n.loop(ticks, age -> {
                if (age >= ticks || !BroomLink.riding(pl)) { end.run(); return false; }
                return true;
            }, end);
            return true;
        });

        // 376 봉화 신호 (SignalFlare): the pillar is the static system at the feet; 30 s the caster shows on party members' maps
        m.put("signal_flare", n -> {
            Player pl = n.pl;
            double mana = n.d("mana", 20);
            int ticks = n.i("ticks", 600);
            List<Player> party = party(n);
            if (party.isEmpty()) { n.warn("이 서버에 신호를 받을 파티원이 없어요"); n.refund(mana); return false; }
            String name = ChacaLink.name(pl.getUniqueId());
            for (Player member : party) {
                ChacaLink.notify(member, "system", "magic-mythic:signal_flare:" + pl.getUniqueId(), "봉화 신호",
                    name + " 님이 봉화를 올렸어요. 30초 동안 지도에 위치가 표시돼요", 3000);
            }
            n.info("파티원 " + party.size() + "명에게 30초 동안 위치를 알려요");
            Set<UUID> sent = new HashSet<>();
            n.loop(ticks, age -> {
                if (age % 20 != 0) return true;
                Location at = pl.getLocation();
                for (Player member : party(n)) {
                    if (member.getWorld() != pl.getWorld()) continue;
                    ping(member, pl, name, at, Math.min(40, ticks - age + 1));
                    sent.add(member.getUniqueId());
                }
                return true;
            }, () -> {
                for (UUID id : sent) { Player member = Bukkit.getPlayer(id); if (member != null) ping(member, pl, name, pl.getLocation(), 0); }
            });
            return true;
        });

        // 380 순풍 (Tailwind): on a broom only. At the release (+0.24 s) every party member riding within 8 (incl. the caster):
        // balance gauge full + 15 s balance loss x0.7, tailwind_buff attached on each one it reached
        m.put("tailwind", n -> {
            Player pl = n.pl;
            double mana = n.d("mana", 50), r = n.d("r", 8);
            int ticks = n.i("ticks", 300);
            float drain = (float) n.d("drain", 0.7);
            if (!BroomLink.riding(pl)) { n.warn("빗자루를 타고 있을 때만 쓸 수 있어요"); n.refund(mana); return false; }
            Location center = pl.getLocation();
            n.later(4, () -> {
                int count = 0;
                List<Player> riders = new ArrayList<>(n.allies(center, r, 16));
                if (!riders.contains(pl)) riders.add(0, pl);
                for (Player p : riders) {
                    if (!BroomLink.riding(p) || !BroomLink.balanceBuff(p, drain, ticks, true)) continue;
                    n.fx("tailwind_buff", p.getLocation(), p);
                    if (p != pl) ChacaLink.notify(p, "system", "magic-mythic:tailwind:" + pl.getUniqueId(), "순풍",
                        ChacaLink.name(pl.getUniqueId()) + " 님의 순풍: 15초 동안 빗자루 균형이 덜 흔들려요", 3000);
                    count++;
                }
                if (count == 0) n.warn("빗자루 모드가 오래되어 순풍을 쓸 수 없어요");
                else if (count > 1) n.info("순풍이 " + count + "명의 빗자루를 받쳐 줘요");
            });
            return true;
        });

        // 385 수확제의 종 (HarvestBell): from the first ring (+0.775 s) 1 min: my harvest wakes crops x2, each woken crop marked
        m.put("harvest_bell", n -> {
            int ticks = n.i("ticks", 1200);
            double chance = n.d("chance", 2);
            n.later(15, () -> Crops.bell(n, ticks, chance));
            return true;
        });

        // 387 작물 붙잡기 (CropSnare): 2 min: crops woken by my harvest are rooted 3 s the tick they come out
        m.put("crop_snare", n -> {
            Crops.snare(n, n.i("ticks", 2400), n.i("root", 60));
            return true;
        });
    }

    static void enable(MagicMythicPlugin plugin) {
        var messenger = plugin.getServer().getMessenger();
        if (!messenger.isOutgoingChannelRegistered(plugin, MAP_PING)) messenger.registerOutgoingPluginChannel(plugin, MAP_PING);
        Crops.plugin = plugin;
        plugin.getServer().getPluginManager().registerEvents(new Crops(), plugin);
    }

    /** Online party members on this server (match: team), without the caster. */
    private static List<Player> party(Nova n) {
        var allies = n.plugin.allies();
        var out = new ArrayList<Player>();
        if (allies == null) return out;
        for (Player p : Bukkit.getOnlinePlayers()) if (p != n.pl && allies.allied(n.pl, p)) out.add(p);
        return out;
    }

    /** MapPingClient frame: uuid;name;world;x;z;ttl ticks (0 = remove). */
    private static void ping(Player to, Player who, String name, Location at, int ttl) {
        if (!to.isOnline() || !to.getListeningPluginChannels().contains(MAP_PING)) return;
        String safe = name.replace(';', ' ');
        if (safe.length() > 32) safe = safe.substring(0, 32);
        String text = who.getUniqueId() + ";" + safe + ";" + at.getWorld().getName() + ";"
            + String.format(Locale.ROOT, "%.1f;%.1f;%d", at.getX(), at.getZ(), Math.max(0, ttl));
        to.sendPluginMessage(Crops.plugin, MAP_PING, text.getBytes(StandardCharsets.UTF_8));
    }

    /** 385 / 387 state: the latest cast per player and until which server tick it lasts; metadata for ChAcademiaWildlife. */
    static final class Crops implements Listener {
        static final String CHANCE = "chacademia_crop_chance", SPAWNED = "chacademia_crop_spawned";
        static MagicMythicPlugin plugin;
        private static final Map<UUID, Nova> bell = new HashMap<>(), snare = new HashMap<>();
        private static final Map<UUID, Long> bellUntil = new HashMap<>(), snareUntil = new HashMap<>();
        private static final Map<UUID, Integer> rootTicks = new HashMap<>();

        static void bell(Nova n, int ticks, double chance) {
            Player p = n.pl;
            UUID id = p.getUniqueId();
            bell.put(id, n); bellUntil.put(id, (long) Bukkit.getCurrentTick() + ticks);
            p.setMetadata(CHANCE, new FixedMetadataValue(plugin, Math.max(1, chance)));
            hook(p);
            n.info("1분 동안 거둔 작물이 더 잘 살아나요");
            n.later(ticks, () -> expire(p));
        }

        static void snare(Nova n, int ticks, int root) {
            Player p = n.pl;
            UUID id = p.getUniqueId();
            snare.put(id, n); snareUntil.put(id, (long) Bukkit.getCurrentTick() + ticks); rootTicks.put(id, root);
            hook(p);
            n.info("2분 동안 살아난 작물이 뿌리에 묶여요");
            n.later(ticks, () -> expire(p));
        }

        private static void hook(Player p) {
            UUID id = p.getUniqueId();
            Consumer<Entity> onSpawn = e -> spawned(id, e);
            p.setMetadata(SPAWNED, new FixedMetadataValue(plugin, onSpawn));
        }

        /** A crop mob just came out of this player's harvest (ChAcademiaWildlife, main thread, the spawn tick). */
        private static void spawned(UUID id, Entity e) {
            if (!(e instanceof LivingEntity crop) || !crop.isValid()) return;
            long now = Bukkit.getCurrentTick();
            Nova b = bell.get(id);
            if (b != null && bellUntil.getOrDefault(id, 0L) > now) {
                b.fx("reveal", crop.getLocation());
                crop.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 600, 0, false, false));
            }
            Nova s = snare.get(id);
            if (s != null && snareUntil.getOrDefault(id, 0L) > now) {
                s.status(crop, "root:" + rootTicks.getOrDefault(id, 60));
                s.fx("crop_snare_bind", crop.getLocation());
            }
        }

        /** Drops what ended (a newer cast keeps its own time); removes the metadata when nothing is left. */
        private static void expire(Player p) {
            UUID id = p.getUniqueId();
            long now = Bukkit.getCurrentTick();
            if (bellUntil.getOrDefault(id, 0L) <= now) {
                bell.remove(id); bellUntil.remove(id);
                p.removeMetadata(CHANCE, plugin);
            }
            if (snareUntil.getOrDefault(id, 0L) <= now) { snare.remove(id); snareUntil.remove(id); rootTicks.remove(id); }
            if (!bell.containsKey(id) && !snare.containsKey(id)) p.removeMetadata(SPAWNED, plugin);
        }

        @EventHandler public void quit(PlayerQuitEvent e) {
            Player p = e.getPlayer();
            UUID id = p.getUniqueId();
            bell.remove(id); bellUntil.remove(id); snare.remove(id); snareUntil.remove(id); rootTicks.remove(id);
            p.removeMetadata(CHANCE, plugin);
            p.removeMetadata(SPAWNED, plugin);
        }
    }
}
