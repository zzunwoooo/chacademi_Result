package kr.kkaegul.core.jail;

import kr.kkaegul.core.KkaegulCore;
import kr.kkaegul.core.data.PlayerData;
import kr.kkaegul.core.util.Json;
import kr.kkaegul.core.util.Locs;
import kr.kkaegul.core.util.Rng;
import kr.kkaegul.core.util.Txt;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 감옥(깜빵).
 *  - 들어오면: 원래 위치 저장, 앉았다 일어났다 40~80회 랜덤, 아이템은 그대로.
 *  - 웅크리기(쉬프트) 1회 = 1회. 다 채우면 나가기 NPC 사용 가능.
 *  - 나가기 NPC: [1] 로비로  [2] 원래 자리로(50% 성공 "아 까비ㅋ" / 실패 "못돌아가쥬~" → 로비)
 *  - 감옥 안에서만 다이아 증가 게임(꽝/x1/x2/x3/x5).
 */
public final class JailService {
    private final KkaegulCore plugin;
    private final Map<UUID, Long> lastSquat = new ConcurrentHashMap<>();
    private final Map<UUID, Long> gameCooldown = new ConcurrentHashMap<>();

    public JailService(KkaegulCore plugin) { this.plugin = plugin; }

    public void send(Player p, String reason) {
        PlayerData d = plugin.data().get(p);
        Location jail = plugin.places().get("jail");
        if (jail == null) {
            plugin.getLogger().warning("감옥 위치가 없어요: /kg setloc jail");
            plugin.ui().error(p, "&c[깨굴] 감옥 위치가 설정되지 않았어요.");
            return;
        }
        boolean already = d.inJail;
        if (!d.inJail) d.jailReturn = Locs.toStr(p.getLocation());
        d.inJail = true;
        int add = Rng.range(plugin.getConfig().getInt("jail.squat-min", 40), plugin.getConfig().getInt("jail.squat-max", 80));
        if (already) {   // 감옥에 있는데 또 걸리면 남은 횟수에 누적
            d.squatRequired += add;
        } else {
            d.squatRequired = add;
            d.squatDone = 0;
        }
        d.jailExitReady = false;
        d.jailCount++;
        d.markDirty();
        p.teleport(jail);
        plugin.ui().center(p, already ? "&c깜빵 추가!" : "&c깜빵에서 운동하세요", "&f앉았다 일어났다 " + (already ? "&e+" + add + "회 &7(" + d.squatDone + "/" + d.squatRequired + ")" : "&e" + d.squatRequired + "회"));
        plugin.ui().open(p, "jail", status(d));
        plugin.ui().error(p, "&c[깜빵] &f{e:shift} 웅크리기를 &e" + (d.squatRequired - d.squatDone) + "회 &f더 하면 나갈 수 있어요." + (reason == null ? "" : " &7(" + reason + ")"));
        plugin.titles().check(d);
        if (plugin.getConfig().getBoolean("jail.game.auto-on-enter", false)) playGame(p, true);
    }

    public Json status(PlayerData d) {
        return Json.obj().put("inJail", d.inJail).put("done", d.squatDone).put("required", d.squatRequired)
                .put("exitReady", d.jailExitReady).put("count", d.jailCount);
    }

    /** 웅크리기 시작 시 호출 */
    public void onSquat(Player p) {
        PlayerData d = plugin.data().get(p);
        if (!d.inJail || d.jailExitReady) return;
        long now = System.currentTimeMillis();
        long min = plugin.getConfig().getLong("jail.squat-min-interval-ms", 250);
        Long last = lastSquat.get(p.getUniqueId());
        if (last != null && now - last < min) return; // 매크로 방지
        lastSquat.put(p.getUniqueId(), now);
        d.squatDone++;
        d.markDirty();
        if (d.squatDone >= d.squatRequired) {
            d.jailExitReady = true;
            plugin.ui().center(p, "&a운동 완료!", "&f{e:npc} 출구 NPC를 눌러 나가세요");
        } else {
            if (!plugin.ui().hasRenderer(p)) plugin.ui().actionbar(p, "&e운동 &f" + d.squatDone + " &7/ " + d.squatRequired);
        }
        plugin.ui().update(p, "jail", status(d));
    }

    /** 출구 NPC */
    public void openExit(Player p) {
        PlayerData d = plugin.data().get(p);
        if (!d.inJail) { p.sendMessage(Txt.color(Txt.notice("&7감옥에 있지 않아요."))); return; }
        if (!d.jailExitReady) {
            plugin.ui().center(p, "&c아직 못 나가요", "&f운동 " + d.squatDone + " / " + d.squatRequired);
            return;
        }
        if (!plugin.ui().send(p, Json.obj().put("type", "open").put("screen", "jail_exit").put("data", status(d)))) {
            plugin.ui().center(p, "&a운동 완료!", "&f{e:npc} 로비 NPC 또는 복귀 NPC(50%)를 누르세요");
        }
    }

    /** choice: lobby | return */
    public void exit(Player p, String choice) {
        PlayerData d = plugin.data().get(p);
        if (!d.inJail || !d.jailExitReady) { openExit(p); return; }
        d.inJail = false;
        d.jailExitReady = false;
        d.markDirty();
        Location back = Locs.fromStr(d.jailReturn);
        if ("return".equalsIgnoreCase(choice) && back != null) {
            if (Rng.chance(plugin.getConfig().getDouble("jail.return-chance", 50))) {
                p.teleport(back);
                plugin.ui().taunt(p, plugin.getConfig().getString("jail.msg-return-success", "&a아 까비ㅋ"), "&f원래 자리로 돌아왔어요");
                plugin.ui().close(p, "jail");
                return;
            }
            plugin.ui().taunt(p, plugin.getConfig().getString("jail.msg-return-fail", "&c못돌아가쥬~"), "&f로비로 이동합니다");
        }
        Location lobby = plugin.places().lobby();
        if (lobby != null) p.teleport(lobby);
        plugin.ui().close(p, "jail");
    }

    /** 감옥 탈출 방지: 감옥 반경 밖이면 되돌림 */
    /** 감옥 구역 안인지 (감옥 위치 반경) */
    public boolean inJailArea(Location to) {
        Location jail = plugin.places().get("jail");
        if (jail == null || to == null || to.getWorld() == null || !to.getWorld().equals(jail.getWorld())) return false;
        double r = plugin.getConfig().getDouble("jail.radius", 15);
        return jail.distanceSquared(to) <= r * r;
    }

    public boolean outOfBounds(Player p, Location to) {
        PlayerData d = plugin.data().get(p);
        if (!d.inJail) return false;
        Location jail = plugin.places().get("jail");
        if (jail == null) return false; // 위치 미설정이면 가두지 않음 (멈춤 방지)
        if (to.getWorld() == null || !to.getWorld().equals(jail.getWorld())) return true;
        double r = plugin.getConfig().getDouble("jail.radius", 15);
        return to.distanceSquared(jail) > r * r;
    }

    public void forceInside(Player p) {
        Location jail = plugin.places().get("jail");
        if (jail != null) p.teleport(jail);
    }

    /** 다이아 증가 게임 (감옥 안에서만) */
    public void playGame(Player p, boolean forced) {
        PlayerData d = plugin.data().get(p);
        if (!d.inJail) { plugin.ui().error(p, "&c다이아 증가 게임은 감옥에서만 할 수 있어요."); return; }
        long now = System.currentTimeMillis();
        long cd = plugin.getConfig().getLong("jail.game.cooldown-seconds", 30) * 1000L;
        Long last = gameCooldown.get(p.getUniqueId());
        if (!forced && last != null && now - last < cd) {
            p.sendMessage(Txt.color(Txt.notice("&7잠시 후 다시 할 수 있어요 (" + ((cd - (now - last)) / 1000) + "초)")));
            return;
        }
        gameCooldown.put(p.getUniqueId(), now);
        ConfigurationSection mult = plugin.getConfig().getConfigurationSection("jail.game.multipliers");
        Map<String, Integer> w = new LinkedHashMap<>();
        if (mult != null) for (String k : mult.getKeys(false)) w.put(k, mult.getInt(k));
        String pick = Rng.weighted(w);
        int m = pick == null ? 0 : parseMult(pick);
        long base = Rng.range(plugin.getConfig().getLong("jail.game.base-min", 10), plugin.getConfig().getLong("jail.game.base-max", 100));
        long amount = base * m;
        Json fx = Json.obj().put("multiplier", m).put("label", m == 0 ? "꽝" : "X" + m).put("base", base).put("amount", amount);
        plugin.ui().fx(p, "jail_game", fx);
        long delay = plugin.getConfig().getLong("jail.game.reveal-ticks", 60);
        boolean gui = plugin.ui().hasRenderer(p);
        if (gui) delay = Math.max(delay, 60);   // 화면 슬롯이 멈추는 시간(2.8초)에 맞춤
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (m == 0) { if (!gui) plugin.ui().center(p, "&7꽝", "&f다행이네요"); }
            else {
                plugin.diamonds().increase(d, amount, "감옥 증가게임 X" + m);
                if (!gui) plugin.ui().center(p, "&cX" + m + "  +" + Txt.num(amount), "&f다이아 증가 게임");
            }
        }, delay);
    }

    private int parseMult(String k) {
        String s = k.toLowerCase().replace("x", "").trim();
        if (s.equals("miss") || s.equals("꽝") || s.isEmpty()) return 0;
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 0; }
    }

    public void release(Player p) {
        PlayerData d = plugin.data().get(p);
        d.inJail = false;
        d.jailExitReady = false;
        d.markDirty();
        Location lobby = plugin.places().lobby();
        if (lobby != null) p.teleport(lobby);
        plugin.ui().close(p, "jail");
    }
}
