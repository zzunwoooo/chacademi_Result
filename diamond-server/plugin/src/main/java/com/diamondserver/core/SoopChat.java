package com.diamondserver.core;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * [기능:서버 채팅 투표 시작]
 * SOOP 방송 채팅을 서버가 직접 읽는다 (공개 채팅을 시청자처럼 읽기만, 로그인·글쓰기 없음).
 * 11/23: 스트리머 게임(모드)이 후원 모드 연결로 채팅을 세던 방식은 실제 방송에서 한 표도 못 셌음 → 서버가 직접 센다.
 * 방송국 아이디는 config.yml vote.soop-ids. 투표가 시작될 때 붙고 끝나면 끊는다.
 * 한 시청자가 여러 번 쳐도 마지막 것 1표 (아이디 기준, 여러 방송을 합쳐도 같은 아이디는 1표).
 */
final class SoopChat {
    private static final String F = "\f", ESC = "\u001b\t";

    private final DiamondPlugin pl;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "diamond-soop-chat"); t.setDaemon(true); return t; });
    private final List<WebSocket> open = new CopyOnWriteArrayList<>();
    private final Map<String, Integer> votes = new ConcurrentHashMap<>();
    private volatile boolean counting;

    SoopChat(DiamondPlugin pl) { this.pl = pl; }

    /** 투표 시작: 설정된 방송마다 채팅에 붙는다 */
    void start() {
        stop();
        votes.clear();
        counting = true;
        List<String> ids = pl.getConfig().getStringList("vote.soop-ids");
        if (ids.isEmpty()) { pl.getLogger().warning("[채팅 투표] config vote.soop-ids 가 비어 있어 SOOP 채팅을 읽지 않습니다."); return; }
        for (String id : ids) if (id != null && !id.isBlank()) connect(id.trim(), (user, text) -> consider(user, text));
    }

    /** 투표 끝: 연결을 모두 끊는다 */
    void stop() {
        counting = false;
        for (WebSocket w : open) w.abort();
        open.clear();
    }

    int count(int v) {
        int n = 0;
        for (int x : votes.values()) if (x == v) n++;
        return n;
    }

    private void consider(String user, String text) {
        if (!counting || user == null || text == null) return;
        String t = text.replace("\r", "").trim().replace('１', '1').replace('２', '2');
        if (t.endsWith("번")) t = t.substring(0, t.length() - 1).trim();
        if (t.equals("1") || t.equals("2")) votes.put(user, t.equals("1") ? 1 : 2);
    }

    /** 관리자 시험: 그 방송 채팅에 secs 초 붙어서 받은 채팅 수와 몇 줄을 알려 줌 */
    void test(String bid, int secs, java.util.function.Consumer<String> say) {
        List<String> sample = new CopyOnWriteArrayList<>();
        int[] n = {0};
        WebSocket[] ws = new WebSocket[1];
        connect(bid, (user, text) -> { n[0]++; if (sample.size() < 3) sample.add(user + ": " + text); }, w -> ws[0] = w, say);
        timer.schedule(() -> {
            if (ws[0] != null) { ws[0].abort(); open.remove(ws[0]); }
            say.accept("SOOP " + bid + " 채팅 " + secs + "초 동안 " + n[0] + "줄 받음" + (sample.isEmpty() ? "" : " · 예: " + String.join(" / ", sample)));
        }, secs, TimeUnit.SECONDS);
    }

    private void connect(String bid, BiConsumer<String, String> onChat) {
        connect(bid, onChat, w -> { }, s -> pl.getLogger().info("[채팅 투표] " + s));
    }

    private void connect(String bid, BiConsumer<String, String> onChat, java.util.function.Consumer<WebSocket> onOpen, java.util.function.Consumer<String> log) {
        String enc = URLEncoder.encode(bid, StandardCharsets.UTF_8);
        String form = "bid=" + enc + "&type=live&pwd=&player_type=html5&stream_type=common&quality=HD&mode=landing&from_api=0&is_revive=false";
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://live.sooplive.co.kr/afreeca/player_live_api.php?bjid=" + enc))
                .timeout(Duration.ofSeconds(8))
                .header("Content-Type", "application/x-www-form-urlencoded").header("User-Agent", "Mozilla/5.0")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build();
        http.sendAsync(req, HttpResponse.BodyHandlers.ofString()).thenAccept(r -> {
            String b = r.body();
            String result = val(b, "RESULT"), dom = val(b, "CHDOMAIN"), chatNo = val(b, "CHATNO"), chpt = val(b, "CHPT");
            if (!"1".equals(result) || dom == null || chatNo == null || chpt == null) { log.accept("SOOP " + bid + ": 방송이 꺼져 있거나 찾지 못함 (RESULT " + result + ")"); return; }
            String url = "wss://" + dom.toLowerCase() + ":" + (Integer.parseInt(chpt.trim()) + 1) + "/Websocket/" + bid;
            http.newWebSocketBuilder().subprotocols("chat").buildAsync(URI.create(url), new Listener(chatNo, onChat)).thenAccept(w -> {
                open.add(w);
                onOpen.accept(w);
                send(w, 1, F + F + F + "16" + F);                                          // 손님 로그인 → 응답이 오면 채팅방 입장
                timer.scheduleAtFixedRate(() -> {
                    if (!open.contains(w)) throw new IllegalStateException("closed");   // 끊긴 연결은 반복을 멈춤
                    send(w, 0, F);
                }, 20, 20, TimeUnit.SECONDS);
                log.accept("SOOP " + bid + " 채팅에 붙음");
            }).exceptionally(e -> { log.accept("SOOP " + bid + " 채팅 연결 실패: " + e); return null; });
        }).exceptionally(e -> { log.accept("SOOP " + bid + " 방송 정보 실패: " + e); return null; });
    }

    private static void send(WebSocket w, int svc, String body) {
        int len = body.getBytes(StandardCharsets.UTF_8).length;
        w.sendBinary(ByteBuffer.wrap((ESC + String.format("%04d%06d00", svc, len) + body).getBytes(StandardCharsets.UTF_8)), true);
    }

    /** 받은 신호: 헤더 14바이트(ESC·종류 4자리·길이 6자리·결과 2자리) + 칸들(\f 로 나뉨). 5 = 채팅 (칸 1 = 글, 칸 2 = 아이디) */
    private static final class Listener implements WebSocket.Listener {
        private final String chatNo;
        private final BiConsumer<String, String> onChat;
        private final java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        Listener(String chatNo, BiConsumer<String, String> onChat) { this.chatNo = chatNo; this.onChat = onChat; }

        @Override public CompletionStage<?> onBinary(WebSocket w, ByteBuffer data, boolean last) {
            byte[] x = new byte[data.remaining()];
            data.get(x);
            buf.write(x, 0, x.length);
            if (last) {
                try { handle(w, new String(buf.toByteArray(), StandardCharsets.UTF_8)); } catch (RuntimeException ignored) { }
                buf.reset();
            }
            w.request(1);
            return null;
        }

        private void handle(WebSocket w, String s) {
            if (s.length() < 14) return;
            int svc = Integer.parseInt(s.substring(2, 6));
            if (svc == 1) { send(w, 2, F + chatNo + F + F + F + F + F); return; }
            if (svc != 5) return;
            String[] f = s.substring(14).split(F, -1);
            if (f.length > 2) onChat.accept(f[2], f[1]);
        }
    }

    private static String val(String b, String k) {
        if (b == null) return null;
        int i = b.indexOf("\"" + k + "\"");
        if (i < 0) return null;
        int c = b.indexOf(':', i) + 1;
        while (c < b.length() && (b.charAt(c) == ' ' || b.charAt(c) == '"')) c++;
        int e = c;
        while (e < b.length() && ",\"}".indexOf(b.charAt(e)) < 0) e++;
        return b.substring(c, e);
    }

    void shutdown() { stop(); timer.shutdownNow(); }
}
// [기능:서버 채팅 투표 끝]
