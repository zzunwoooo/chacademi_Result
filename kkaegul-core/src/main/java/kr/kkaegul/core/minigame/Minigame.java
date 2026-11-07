package kr.kkaegul.core.minigame;

import kr.kkaegul.core.KkaegulCore;
import kr.kkaegul.core.util.Json;
import kr.kkaegul.core.util.Txt;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 미니게임 공통: 참가자, 원래 위치 저장/복귀, 보상 헬퍼, 이벤트 훅 */
public abstract class Minigame {
    protected final KkaegulCore plugin;
    public final String id;
    protected final Map<UUID, Location> back = new LinkedHashMap<>();
    protected final List<UUID> players = new ArrayList<>();
    protected boolean running = false;
    private int runId = 0;

    protected Minigame(KkaegulCore plugin, String id) {
        this.plugin = plugin;
        this.id = id;
    }

    public abstract String label();

    public int minPlayers() { return plugin.getConfig().getInt("minigame.games." + id + ".min-players", 1); }

    public boolean enabled() { return plugin.getConfig().getBoolean("minigame.games." + id + ".enabled", true); }

    /** 이 게임에 뽑힐 수 있는지 (다이아가 있어야 하는 게임 등) */
    public boolean eligible(Player p) { return true; }
    /** 한 번에 받을 수 있는 최대 인원 (자리 수 등). 제한 없으면 큰 수 */
    public int capacity() { return Integer.MAX_VALUE; }

    /** 정해진 시간 동안 이어지는 게임인지 (포커·섯다). /깨굴 게임 시간 으로 조절 */
    public boolean timed() { return false; }

    public int durationMinutes() { return Math.max(1, plugin.getConfig().getInt("minigame.games." + id + ".duration-minutes", 60)); }

    /** 이 게임을 지금 열 수 있는지 (위치 설정 등) */
    public abstract String readiness();

    public final boolean running() { return running; }

    public boolean has(Player p) { return running && players.contains(p.getUniqueId()); }

    public final void begin(List<Player> list) {
        if (running) return;
        running = true;
        runId++;
        players.clear();
        back.clear();
        for (Player p : list) {
            players.add(p.getUniqueId());
            back.put(p.getUniqueId(), p.getLocation().clone());
        }
        try {
            start(list);
        } catch (RuntimeException e) {
            plugin.getLogger().warning("미니게임 시작 오류 " + id + ": " + e);
            end();
        }
    }

    protected abstract void start(List<Player> list);

    /** 정리 + 복귀 */
    public final void end() {
        if (!running) return;
        try {
            cleanup();
        } catch (RuntimeException e) {
            plugin.getLogger().warning("미니게임 정리 오류 " + id + ": " + e);
        }
        running = false;
        for (UUID u : new ArrayList<>(players)) {
            Player p = Bukkit.getPlayer(u);
            Location l = back.get(u);
            if (p != null && l != null) p.teleport(l);
            if (p != null) plugin.ui().close(p, "minigame");
        }
        players.clear();
        back.clear();
        state.clear();
        plugin.minigames().onEnded(this);
    }

    protected void cleanup() {}

    /** 중간 이탈(로그아웃) */
    public void quit(Player p) {
        players.remove(p.getUniqueId());
        Location l = back.remove(p.getUniqueId());
        if (l != null) p.teleport(l);
        if (running && players.isEmpty()) end();
    }

    // ─── 이벤트 훅 (필요한 게임만 오버라이드) ───
    public void onSwing(Player p) {}
    /** false 면 그 움직임을 막음 (대기 구역 등) */
    public boolean allowMove(Player p, Location to) { return true; }
    public boolean onInteractBlock(Player p, Block b) { return false; }

    // ─── 헬퍼 ───
    protected List<Player> online() {
        List<Player> out = new ArrayList<>();
        for (UUID u : players) { Player p = Bukkit.getPlayer(u); if (p != null) out.add(p); }
        return out;
    }

    protected void rewardSuccess(Player p) {
        for (String a : plugin.getConfig().getStringList("minigame.games." + id + ".success-actions"))
            plugin.actions().run(p.getUniqueId(), a, label() + " 성공");
        if (plugin.getConfig().getStringList("minigame.games." + id + ".success-actions").isEmpty())
            plugin.mail().send(p.getUniqueId(), plugin.items().create(kr.kkaegul.core.item.CustomItem.PREMIUM_BOX, 1), label() + " 성공 보상");
    }

    protected void rewardFail(Player p) {
        List<String> acts = plugin.getConfig().getStringList("minigame.games." + id + ".fail-actions");
        if (acts.isEmpty()) plugin.roulette().spin(p.getUniqueId(), "big_increase", label() + " 실패");
        else for (String a : acts) plugin.actions().run(p.getUniqueId(), a, label() + " 실패");
    }

    protected int cfgInt(String key, int def) { return plugin.getConfig().getInt("minigame.games." + id + "." + key, def); }
    protected boolean cfgBool(String key, boolean def) { return plugin.getConfig().getBoolean("minigame.games." + id + "." + key, def); }
    protected double cfgDouble(String key, double def) { return plugin.getConfig().getDouble("minigame.games." + id + "." + key, def); }

    /** 이번 판에서만 실행 (이전 판의 타이머가 다음 판을 끝내지 않게) */
    protected void later(long ticks, Runnable r) {
        int id = runId;
        Bukkit.getScheduler().runTaskLater(plugin, () -> { if (running && runId == id) r.run(); }, ticks);
    }

    /** 도착 뒤 정신 차리는 시간: 액션바에 남은 초 (회색), 마지막 3초는 똑딱 소리 */
    protected void waitPhase(int seconds, String what, Runnable then) {
        seconds = Math.max(0, seconds);
        if (seconds > 0) for (Player p : online()) p.sendMessage(c(Txt.notice("&7" + seconds + "초 뒤 " + what + "! 정신 차리세요.")));
        for (int i = 0; i < seconds; i++) {
            final int left = seconds - i;
            later(i * 20L, () -> {
                for (Player p : online()) {
                    p.sendActionBar(c("&7" + what + "까지 &f" + left + "초"));
                    if (left <= 3) p.playSound(p.getLocation(), "minecraft:block.note_block.hat", 1f, 1.2f);
                }
            });
        }
        later(seconds * 20L, then);
    }

    /** 이 게임이 보호하는 블록인지 (보물상자 등) */
    public boolean protects(Block b) { return false; }

    protected void tell(String title, String sub) { for (Player p : online()) plugin.ui().center(p, title, sub); }

    // ─── 미니게임 HUD (모드 화면) ───
    /** 참가자 상태: wait / success / out */
    protected final java.util.Map<UUID, String> state = new java.util.LinkedHashMap<>();

    protected List<Object> participantsJson() {
        List<Object> arr = new ArrayList<>();
        for (UUID u : players) {
            Player p = Bukkit.getPlayer(u);
            if (p == null) continue;
            arr.add(Json.obj().put("name", plugin.displayName(p)).put("account", p.getName()).put("state", state.getOrDefault(u, "wait")));
        }
        return arr;
    }

    /** 모두에게 HUD 열기 (data 에 제목·참가자 추가) */
    protected void hudOpen(Player p, Json data) {
        plugin.ui().open(p, "minigame", data.put("game", id).put("title", label()).put("participants", participantsJson()));
    }

    /** 참가자 상태 바꾸고 모두에게 반영 */
    protected void setState(UUID u, String st) {
        state.put(u, st);
        List<Object> arr = participantsJson();
        for (Player o : online()) plugin.ui().update(o, "minigame", Json.obj().put("participants", arr));
    }

    /** 결과 연출 (개구리 + 리본 + 보상 카드). 창이 없으면 가운데 멘트 */
    protected void resultFx(Player p, boolean ok, String sub, String... rewards) {
        if (plugin.ui().hasRenderer(p)) {
            List<Object> rw = new ArrayList<>();
            for (String r : rewards) rw.add(Json.obj().put("label", r).put("premium", r.contains("고급") || r.contains("행운")));
            plugin.ui().fx(p, "minigame_result", Json.obj().put("success", ok).put("title", label()).put("sub", sub).put("rewards", rw));
        } else plugin.ui().center(p, ok ? "&a성공!" : "&c실패…", "&f" + sub);
    }

    protected static String c(String s) { return Txt.color(s); }
}
