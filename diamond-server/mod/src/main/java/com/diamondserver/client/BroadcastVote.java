package com.diamondserver.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 3000개 채팅 투표: 방송 채팅에서 "1"·"2" 를 센다.
 * 방송 연결은 실행기에 들어 있는 후원 모드(Donation, bot.colla)가 이미 해 두었으므로 그 연결에 붙어서 읽기만 한다 (로그인·주소 등록 없음).
 *  - SOOP: 후원 모드의 채팅 연결(netty)에 "diamond-vote" 처리기를 끼워, 지나가는 신호에서 채팅 글을 읽는다 (후원 모드에는 그대로 넘김).
 *  - 치지직: 후원 모드가 세션을 열 때 받은 세션 키(ChzzkClientMixin 이 넘겨줌)로 채팅 구독을 걸고, 같은 소켓에서 CHAT 을 듣는다.
 * 한 시청자가 여러 번 쳐도 마지막 것 1표. 세는 것은 서버가 투표를 시작했을 때부터 끝날 때까지만.
 * 후원 모드가 없거나 방식이 바뀌어 붙지 못하면 조용히 0표로 남는다 (게임에는 영향 없음).
 */
public final class BroadcastVote {
    private static volatile boolean counting;
    private static final Map<String, Integer> votes = new ConcurrentHashMap<>();    // "soop:아이디" / "chzzk:채널id" → 1·2
    private static final Set<Integer> loggedSvc = ConcurrentHashMap.newKeySet();
    private static int tick;
    private static String lastSent = "";

    private BroadcastVote() { }

    public static void start() { votes.clear(); counting = true; tick = 0; lastSent = ""; log("투표 세기 시작"); }

    public static void stop() {
        if (counting) log("투표 세기 끝: 1=" + count(1) + " 2=" + count(2));
        counting = false;
    }

    private static int count(int v) {
        int n = 0;
        for (int x : votes.values()) if (x == v) n++;
        return n;
    }

    /** 매 틱: 투표 중이면 1초마다 방송 연결에 붙어 있는지 확인하고, 지금까지 센 수를 서버에 보낸다 */
    public static void tick() {
        if (!counting) return;
        if (tick++ % 20 != 0) return;
        hookSoop();
        String now = count(1) + ":" + count(2);
        JsonObject o = new JsonObject();
        o.addProperty("t", "vote.tally");
        o.addProperty("a", count(1));
        o.addProperty("b", count(2));
        if (!now.equals(lastSent) || tick % 100 == 1) { DiamondClient.send(o); lastSent = now; }
    }

    /** 채팅 한 줄을 표로 셈 (1·2 만, "1번"·"２" 도 인정) */
    static void consider(String platform, String voter, String text) {
        if (!counting || voter == null || text == null) return;
        String t = text.trim().replace('１', '1').replace('２', '2');
        if (t.endsWith("번")) t = t.substring(0, t.length() - 1).trim();
        if (t.equals("1") || t.equals("2")) votes.put(platform + ":" + voter, t.equals("1") ? 1 : 2);
    }

    // ── SOOP ───────────────────────────────────────────────
    private static final String SOOP_TAP = "diamond-vote", SOOP_BEFORE = "[INPUT] Packet decoder";
    /** 후원 모드가 받아서 쓰는 신호 종류(후원·연결 유지·입장). 채팅이 아니므로 건너뛴다 */
    private static final Set<Integer> SOOP_DONATION = Set.of(0, 1, 2, 9, 14, 22, 25, 26, 27, 28, 34, 35, 36);

    private static void hookSoop() {
        try {
            Object service = service("SOOP");
            Object client = field(service, "client");
            Object socket = client == null ? null : field(client, "socket");
            Object ch = socket == null ? null : field(socket, "channel");
            if (!(ch instanceof Channel channel) || !channel.isActive()) return;
            ChannelPipeline p = channel.pipeline();
            if (p.get(SOOP_TAP) == null && p.get(SOOP_BEFORE) != null) { p.addBefore(SOOP_BEFORE, SOOP_TAP, new SoopTap()); log("SOOP 채팅 연결에 붙음"); }
        } catch (ClassNotFoundException e) {
            // 후원 모드가 없음
        } catch (Throwable e) {
            log("SOOP 연결에 붙지 못함: " + e);
        }
    }

    /** 지나가는 SOOP 신호를 엿보고 그대로 넘긴다. 신호 = A5 · 종류(2) · 길이(2) · 결과(2) · 값들(A1 글자 / A9 정수 / A8 짧은 정수) */
    private static final class SoopTap extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (counting && msg instanceof ByteBuf buf) {
                try { read(buf.duplicate()); } catch (RuntimeException ignored) { }
            }
            super.channelRead(ctx, msg);
        }

        private static void read(ByteBuf b) {
            if (b.readableBytes() < 7 || b.readByte() != (byte) 0xA5) return;
            int svc = b.readShortLE();
            b.skipBytes(2);
            if (b.readShortLE() != 0 || SOOP_DONATION.contains(svc)) return;
            java.util.List<String> strings = new java.util.ArrayList<>();
            for (int i = 0; i < 24 && b.readableBytes() > 0; i++) {
                int tag = b.readByte() & 0xFF;
                if (tag == 0xA1) {
                    int len = b.bytesBefore((byte) 0);
                    if (len < 0) break;
                    byte[] raw = new byte[len];
                    b.readBytes(raw);
                    b.skipBytes(1);
                    strings.add(new String(raw, StandardCharsets.UTF_8));
                } else if (tag == 0xA9) b.skipBytes(4);
                else if (tag == 0xA8) b.skipBytes(2);
                else break;
            }
            if (loggedSvc.add(svc)) log("SOOP 신호 종류 " + svc + " (글자 " + strings.size() + "개)");
            // 채팅 글 = 정확히 1·2 인 글자 칸. 보낸 사람 = 첫 번째 글자 칸(아이디)
            for (int i = 1; i < strings.size(); i++) {
                String s = strings.get(i).trim();
                if (s.equals("1") || s.equals("2") || s.equals("1번") || s.equals("2번") || s.equals("１") || s.equals("２")) {
                    consider("soop", strings.get(0), s);
                    return;
                }
            }
        }
    }

    // ── 치지직 ─────────────────────────────────────────────
    private static final Set<Object> chzzkHooked = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    /** 후원 모드의 치지직 연결이 SYSTEM 신호를 받을 때 (ChzzkClientMixin). connected 면 같은 소켓에 채팅 구독을 건다 */
    public static void onChzzkSystem(Object client, Object[] args) {
        try {
            if (args == null || args.length == 0 || args[0] == null) return;
            JsonElement el = JsonParser.parseString(args[0].toString());
            if (!el.isJsonObject()) return;
            JsonObject o = el.getAsJsonObject();
            if (!"connected".equals(o.has("type") ? o.get("type").getAsString() : "")) return;
            JsonObject data = o.has("data") && o.get("data").isJsonObject() ? o.getAsJsonObject("data") : null;
            String key = data != null && data.has("sessionKey") ? data.get("sessionKey").getAsString() : null;
            Object socket = field(client, "socket");
            if (key == null || socket == null) return;
            synchronized (chzzkHooked) {
                if (chzzkHooked.add(socket)) listenChat(socket);
            }
            Object service = field(client, "service");
            Thread t = new Thread(() -> subscribeChat(service, key), "diamond-chzzk-chat");
            t.setDaemon(true);
            t.start();
        } catch (Throwable e) {
            log("치지직 채팅 연결 실패: " + e);
        }
    }

    private static void listenChat(Object socket) throws Exception {
        Class<?> listener = Class.forName("bot.colla.shadow.io.socket.emitter.Emitter$Listener", true, socket.getClass().getClassLoader());
        Object l = Proxy.newProxyInstance(listener.getClassLoader(), new Class<?>[]{listener}, (proxy, m, a) -> {
            if (m.getName().equals("call") && a != null && a.length > 0 && a[0] instanceof Object[] args && args.length > 0 && args[0] != null) {
                try {
                    JsonObject c = JsonParser.parseString(args[0].toString()).getAsJsonObject();
                    String who = c.has("senderChannelId") ? c.get("senderChannelId").getAsString() : null;
                    String text = c.has("content") ? c.get("content").getAsString() : null;
                    consider("chzzk", who, text);
                } catch (RuntimeException ignored) { }
            }
            if (m.getDeclaringClass() == Object.class) {
                return switch (m.getName()) { case "hashCode" -> System.identityHashCode(proxy); case "equals" -> proxy == a[0]; default -> "diamond-chat-listener"; };
            }
            return null;
        });
        Method on = socket.getClass().getMethod("on", String.class, listener);
        on.invoke(socket, "CHAT", l);
        log("치지직 CHAT 듣기 시작");
    }

    private static void subscribeChat(Object service, String sessionKey) {
        try {
            Method send = findMethod(service.getClass(), "sendRequest");
            if (send == null) { log("치지직 채팅 구독: 요청 함수를 찾지 못함"); return; }
            send.setAccessible(true);
            java.util.function.BiFunction<Integer, JsonObject, Boolean> ok = (code, body) -> code != null && code == 200;
            Object r = send.invoke(service, "POST", "/open/v1/sessions/events/subscribe/chat?sessionKey=" + sessionKey, ok);
            log("치지직 채팅 구독 " + (Boolean.TRUE.equals(r) ? "성공" : "실패 (후원 모드 앱에 채팅 조회 권한이 없을 수 있음)"));
        } catch (Throwable e) {
            log("치지직 채팅 구독 오류: " + e);
        }
    }

    // ── 후원 모드 안을 들여다보는 도우미 ───────────────────────
    private static Object service(String platform) throws Exception {
        Class<?> plat = Class.forName("bot.colla.donation.api.Platform");
        for (Object c : plat.getEnumConstants()) if (((Enum<?>) c).name().equals(platform)) return plat.getMethod("getService").invoke(c);
        return null;
    }

    private static Object field(Object o, String name) throws IllegalAccessException {
        if (o == null) return null;
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(o);
            } catch (NoSuchFieldException ignored) { }
        }
        return null;
    }

    private static Method findMethod(Class<?> c, String name) {
        for (; c != null; c = c.getSuperclass()) for (Method m : c.getDeclaredMethods()) if (m.getName().equals(name) && m.getParameterCount() == 3) return m;
        return null;
    }

    private static void log(String s) { System.out.println("[나니 다이아서버 채팅 투표] " + s); }
}
