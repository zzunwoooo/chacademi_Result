package kr.kkaegul.core.harass;

import kr.kkaegul.core.KkaegulCore;
import kr.kkaegul.core.util.Json;
import kr.kkaegul.core.util.Rng;
import kr.kkaegul.core.util.Txt;
import org.bukkit.Bukkit;
import org.bukkit.Input;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * WASD 랜덤 커맨드 입력 챌린지 (서버 측 PlayerInputEvent 로 키 입력 감지 — 1.21.2+).
 * 섭주 괴롭히기 / 위험한 초대 에서 같이 쓴다.
 */
public final class WasdService {
    private final KkaegulCore plugin;
    private final Map<UUID, Challenge> active = new ConcurrentHashMap<>();

    public WasdService(KkaegulCore plugin) { this.plugin = plugin; }

    private static final class Challenge {
        final List<Character> keys;
        int index = 0;
        long deadline;
        int perKey;   // >0 이면 키마다 이 초 안에 눌러야 함 (누를 때마다 다시)
        final Consumer<Player> onSuccess, onFail;
        Input last;
        boolean done = false;

        Challenge(List<Character> keys, long deadline, Consumer<Player> s, Consumer<Player> f) {
            this.keys = keys; this.deadline = deadline; this.onSuccess = s; this.onFail = f;
        }
    }

    public boolean inChallenge(Player p) { return active.containsKey(p.getUniqueId()); }

    public void start(Player p, int length, int seconds, Consumer<Player> onSuccess, Consumer<Player> onFail) {
        start(p, length, seconds, "WASD 챌린지", onSuccess, onFail);
    }

    public void start(Player p, int length, int seconds, String title, Consumer<Player> onSuccess, Consumer<Player> onFail) {
        start(p, length, seconds, 0, title, onSuccess, onFail);
    }

    /** perKey > 0: 키 하나마다 perKey 초 (seconds 무시) */
    public void start(Player p, int length, int seconds, int perKey, String title, Consumer<Player> onSuccess, Consumer<Player> onFail) {
        start(p, length, (double) seconds, perKey, title, onSuccess, onFail);
    }

    /** seconds 는 소수 가능 (2.5초) */
    public void start(Player p, int length, double secondsD, int perKey, String title, Consumer<Player> onSuccess, Consumer<Player> onFail) {
        if (perKey > 0) secondsD = perKey;
        long ms = Math.max(500, Math.round(secondsD * 1000));
        int seconds = (int) Math.ceil(ms / 1000.0);
        List<Character> keys = new ArrayList<>();
        char[] pool = {'W', 'A', 'S', 'D'};
        for (int i = 0; i < length; i++) keys.add(pool[Rng.range(0, 3)]);
        Challenge c = new Challenge(keys, System.currentTimeMillis() + ms, onSuccess, onFail);
        c.perKey = perKey;
        c.last = p.getCurrentInput(); // 이미 누르고 있던 키는 새 입력으로 치지 않음
        active.put(p.getUniqueId(), c);
        StringBuilder seq = new StringBuilder();
        for (char k : keys) seq.append("&f{e:").append(Character.toLowerCase(k)).append("} ");
        if (!plugin.ui().send(p, Json.obj().put("type", "open").put("screen", "wasd")
                .put("data", Json.obj().put("keys", keys.toString()).put("seconds", seconds).put("ms", ms).put("index", 0).put("title", title)))) {
            plugin.ui().center(p, "&e" + seq.toString().trim(), "&f" + (ms % 1000 == 0 ? String.valueOf(ms / 1000) : String.valueOf(ms / 1000.0)) + "초 안에 순서대로 입력!");
        }
        // 시간 초과 감시 (키마다 제한시간이 다시 시작될 수 있어서 5틱마다 확인)
        final org.bukkit.scheduler.BukkitTask[] watch = new org.bukkit.scheduler.BukkitTask[1];
        watch[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            Challenge now = active.get(p.getUniqueId());
            if (now != c || c.done) { watch[0].cancel(); return; }
            if (!p.isOnline() || System.currentTimeMillis() > c.deadline + 100) { watch[0].cancel(); finish(p, c, false); }
        }, 5L, 5L);
    }

    /** PlayerInputEvent 에서 호출 */
    public void onInput(Player p, Input input) {
        Challenge c = active.get(p.getUniqueId());
        if (c == null || c.done) return;
        if (System.currentTimeMillis() > c.deadline) { finish(p, c, false); return; }
        Character pressed = newlyPressed(c.last, input);
        c.last = input;
        if (pressed == null) return;
        char want = c.keys.get(c.index);
        if (pressed != want) { finish(p, c, false); return; }
        c.index++;
        if (c.perKey > 0) c.deadline = System.currentTimeMillis() + c.perKey * 1000L;
        p.playSound(p.getLocation(), "minecraft:block.note_block.hat", 0.8f, 1.2f + 0.1f * c.index);
        plugin.ui().update(p, "wasd", c.perKey > 0 && c.index < c.keys.size() ? Json.obj().put("index", c.index).put("seconds", c.perKey) : Json.obj().put("index", c.index));
        if (!plugin.ui().hasRenderer(p)) plugin.ui().actionbar(p, "&a" + c.index + " / " + c.keys.size());
        if (c.index >= c.keys.size()) finish(p, c, true);
    }

    private Character newlyPressed(Input before, Input now) {
        boolean bw = before != null && before.isForward(), ba = before != null && before.isLeft();
        boolean bs = before != null && before.isBackward(), bd = before != null && before.isRight();
        if (now.isForward() && !bw) return 'W';
        if (now.isLeft() && !ba) return 'A';
        if (now.isBackward() && !bs) return 'S';
        if (now.isRight() && !bd) return 'D';
        return null;
    }

    private void finish(Player p, Challenge c, boolean success) {
        if (c.done) return;
        c.done = true;
        active.remove(p.getUniqueId());
        boolean gui = plugin.ui().hasRenderer(p);
        if (success) plugin.ui().successSound(p); else plugin.ui().errorSound(p);
        Runnable after = () -> {
            if (!p.isOnline()) return;
            if (success) { if (c.onSuccess != null) c.onSuccess.accept(p); }
            else { if (c.onFail != null) c.onFail.accept(p); }
        };
        if (gui) {
            // 키캡 맞음/틀림 1초 → 판 닫고 성공/실패 개구리 → 그게 사라지면 룰렛 (룰렛은 모드가 알아서 기다림)
            plugin.ui().update(p, "wasd", Json.obj().put("index", c.index).put("done", true).put("success", success));
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                plugin.ui().close(p, "wasd");
                plugin.ui().fx(p, "minigame_result", Json.obj().put("success", success).put("title", "WASD 챌린지")
                        .put("sub", success ? "성공! 소소 감소 룰렛" : "실패… 소소 증가 룰렛").put("rewards", new java.util.ArrayList<>()));
                after.run();
            }, 24L);
        } else {
            plugin.ui().close(p, "wasd");
            plugin.ui().center(p, success ? "&a성공!" : "&c실패!", "");
            after.run();
        }
    }

    public void cancel(Player p) { active.remove(p.getUniqueId()); }

    public static String describe(List<Character> keys) { return Txt.strip(keys.toString()); }
}
