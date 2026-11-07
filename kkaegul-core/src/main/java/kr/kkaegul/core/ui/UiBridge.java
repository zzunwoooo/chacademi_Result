package kr.kkaegul.core.ui;

import kr.kkaegul.core.KkaegulCore;
import kr.kkaegul.core.util.Json;
import kr.kkaegul.core.util.Txt;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * 서버 ↔ 고화질 UI 렌더러(클라이언트 모드) 연결 지점.
 *
 * 서버 → 클라: 채널 {@code kkaegul:ui}, UTF-8 JSON 한 개.
 *   {"type":"open","screen":"gacha", "data":{...}}     화면 열기
 *   {"type":"update","screen":"gauge","data":{...}}    HUD/화면 값 갱신
 *   {"type":"center","text":"...", "sub":"...", "ms":2500} 화면 중앙 멘트
 *   {"type":"roulette", ...}                            룰렛 연출
 *   {"type":"fx","id":"jackpot", ...}                   일회성 연출
 *   {"type":"close","screen":"..."}
 * 클라 → 서버: 채널 {@code kkaegul:action}, JSON {"action":"gacha.spin","machine":"2"} 처럼 평평한 객체.
 *
 * 렌더러가 없는 클라이언트(채널 미등록)는 채팅/타이틀로 대체 출력한다.
 * 차카데미 렌더러 규격이 다르면 이 클래스의 {@link #send} 하나만 바꾸면 된다.
 */
public final class UiBridge implements PluginMessageListener {
    public static final String OUT = "kkaegul:ui";
    public static final String IN = "kkaegul:action";

    private final KkaegulCore plugin;
    private final Map<String, BiConsumer<Player, Map<String, String>>> actions = new ConcurrentHashMap<>();

    public UiBridge(KkaegulCore plugin) {
        this.plugin = plugin;
        Bukkit.getMessenger().registerOutgoingPluginChannel(plugin, OUT);
        Bukkit.getMessenger().registerIncomingPluginChannel(plugin, IN, this);
    }

    public boolean hasRenderer(Player p) {
        if (plugin.getConfig().getBoolean("ui.force-fallback", false)) return false;
        return p.getListeningPluginChannels().contains(OUT);
    }

    /** 원시 전송. 렌더러가 있으면 true */
    public boolean send(Player p, Json payload) {
        if (!plugin.isEnabled() || !hasRenderer(p)) return false; // 비활성화 중 플러그인 메시지 전송은 예외
        // 렌더러가 구현한 화면만 보낸다. 나머지는 호출한 쪽의 대체 출력(중앙 멘트/채팅)으로
        Object type = payload.map().get("type");
        if ("open".equals(type) || "update".equals(type)) {
            Object screen = payload.map().get("screen");
            java.util.List<String> allowed = plugin.getConfig().getStringList("ui.renderer-screens");
            if (screen != null && !allowed.isEmpty() && !allowed.contains(String.valueOf(screen))) return false;
        }

        p.sendPluginMessage(plugin, OUT, Txt.emotes(payload.toString()).getBytes(StandardCharsets.UTF_8)); // {e:..} → 이모트 글리프
        return true;
    }

    // ───────────── 고수준 헬퍼 ─────────────

    /** 화면 중앙 멘트 (요청서: "멘트 뜨는건 가운데에") */
    public void center(Player p, String text, String sub) {
        if (send(p, Json.obj().put("type", "center").put("text", text).put("sub", sub == null ? "" : sub).put("ms", 2500))) return;
        p.sendTitle(Txt.color(text), Txt.color(sub == null ? "" : sub), 5, 50, 10);
    }

    public void center(Player p, String text) { center(p, text, ""); }

    /** 놀리는 한마디: 모드 화면 가운데에 크게 통통 튀며 (모드 없으면 타이틀) */
    public void taunt(Player p, String text, String sub) {
        if (send(p, Json.obj().put("type", "taunt").put("text", text).put("sub", sub == null ? "" : sub).put("ms", 2600))) return;
        p.sendTitle(Txt.color(text), Txt.color(sub == null ? "" : sub), 5, 50, 10);
    }

    public void centerAll(String text, String sub) {
        for (Player p : Bukkit.getOnlinePlayers()) center(p, text, sub);
    }

    /** 에러 알림: 빨간 느낌표 카드 + 에러 사운드 (모드 없으면 채팅) */
    public void error(Player p, String text) {
        errorSound(p);
        if (send(p, Json.obj().put("type", "toast").put("text", "&c" + Txt.strip(Txt.color(text.replace("{e:error}", ""))).trim()).put("kind", "error"))) return;
        p.sendMessage(Txt.color(Txt.error(text)));
    }

    /** 자주 쓰는 에러음: 디저리두 2.0 → (3틱) 1.5 */
    /** 성공음 (벨 0.8 → 1.2 → 1.6 → 2.0) */
    public void successSound(Player p) {
        float[] pit = {0.8f, 1.2f, 1.6f, 2.0f};
        long[] at = {0, 3, 6, 10};
        for (int i = 0; i < 4; i++) {
            float pp = pit[i];
            Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.isOnline()) p.playSound(p.getLocation(), "minecraft:block.note_block.bell", 1f, pp); }, at[i]);
        }
    }

    /** 순간이동 소리 (출발 자리·도착 자리 모두) */
    public void teleportSound(Player p, org.bukkit.Location from) {
        if (from != null && from.getWorld() != null) from.getWorld().playSound(from, "minecraft:entity.enderman.teleport", 0.8f, 1.1f);
        Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.isOnline()) p.getWorld().playSound(p.getLocation(), "minecraft:entity.enderman.teleport", 0.8f, 1.1f); }, 1L);
    }

    private final Map<java.util.UUID, Long> lastErrorSound = new ConcurrentHashMap<>();

    public void errorSound(Player p) {
        long now = System.currentTimeMillis();
        Long last = lastErrorSound.put(p.getUniqueId(), now);
        if (last != null && now - last < 700) return;      // 연달아 오면 소리는 한 번만
        p.playSound(p.getLocation(), "minecraft:block.note_block.didgeridoo", 0.7f, 2.0f);
        Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.isOnline()) p.playSound(p.getLocation(), "minecraft:block.note_block.didgeridoo", 0.7f, 1.5f); }, 3L);
    }

    public void actionbar(Player p, String text) {
        if (text != null && Txt.strip(Txt.color(text)).length() > 0 && text.startsWith("&c")) { error(p, text); return; }
        if (send(p, Json.obj().put("type", "toast").put("text", text))) return;
        p.sendActionBar(Txt.color(text));
    }

    /** 화면 열기. 렌더러가 없으면 data 요약을 채팅으로 */
    public void open(Player p, String screen, Json data) {
        if (send(p, Json.obj().put("type", "open").put("screen", screen).put("data", data))) return;
        if (plugin.getConfig().getBoolean("ui.fallback-debug-open", false))
            p.sendMessage(Txt.color("&8[UI:" + screen + "] &7" + Txt.strip(data.toString())));
    }

    public void update(Player p, String screen, Json data) {
        send(p, Json.obj().put("type", "update").put("screen", screen).put("data", data));
    }

    public void close(Player p, String screen) {
        send(p, Json.obj().put("type", "close").put("screen", screen));
    }

    public void fx(Player p, String id, Json data) {
        send(p, Json.obj().put("type", "fx").put("id", id).put("data", data));
    }

    // ───────────── 클라 → 서버 액션 ─────────────

    public void onAction(String action, BiConsumer<Player, Map<String, String>> handler) {
        actions.put(action, handler);
    }

    public boolean dispatch(Player p, String action, Map<String, String> args) {
        BiConsumer<Player, Map<String, String>> h = actions.get(action);
        if (h == null) return false;
        try {
            h.accept(p, args);
        } catch (RuntimeException e) {
            plugin.getLogger().warning("UI 액션 처리 오류 " + action + ": " + e);
        }
        return true;
    }

    public java.util.Set<String> actionNames() { return actions.keySet(); }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!IN.equals(channel)) return;
        Map<String, String> args = Json.parseFlat(new String(message, StandardCharsets.UTF_8));
        String action = args.remove("action");
        if (action == null) return;
        // 패킷 스레드일 수 있으므로 메인 스레드로 넘긴다
        Bukkit.getScheduler().runTask(plugin, () -> dispatch(player, action, args));
    }
}
