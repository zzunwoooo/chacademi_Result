package school.magiccodex.paper;

import java.io.*;
import java.util.*;
import school.magiccodex.protocol.MegaphoneProtocol;

/**
 * 확성기 (오픈 전 메모 #102) 순수 규칙 — Bukkit 없이 시험할 수 있는 부분만.
 * 설정 값 정리 · 금칙어 검사 · 서버 간 메시지(BungeeCord Forward ALL, #95 /공지와 같은 방식, 하위 채널만 다름).
 */
final class MegaphoneRules {
    private MegaphoneRules() {}

    /** 서버 간 메시지의 하위 채널 이름 (/공지 의 ChacaConvenience 와 따로). */
    static final String SUBCHANNEL = "ChacaMegaphone";
    static final int VERSION = 1;
    /** 서버 간 메시지 하나의 최대 크기. 받는 서버의 "클라이언트 → 서버" 플러그인 메시지 한계(32767)보다 작게. */
    static final int MAX_WIRE = 30000;
    /**
     * 설정 max-length 상한 (Unicode code point). 프로토콜 글자 상한(MAX_TEXT, UTF-16 단위)의 절반이라
     * 이모지처럼 두 칸짜리 글자만 써도 넘지 않는다 (넘으면 확성기를 쓰고도 공지를 못 만드는 일이 생김).
     */
    static final int MAX_LENGTH = MegaphoneProtocol.MAX_TEXT / 2;

    /** megaphone.yml 내용. */
    record Settings(String item, long cooldownMs, int maxLength, int showMs, boolean crossServer, long promptTimeoutMs,
                    boolean chacaNpcFilter, List<String> bannedWords) {
        static final Settings DEFAULT = new Settings("item:megaphone", 30_000, 80, 6_000, true, 120_000, true,
                List.of("시발", "씨발", "ㅅㅂ", "ㅆㅂ", "병신", "ㅂㅅ", "지랄", "좆", "존나", "개새끼", "미친놈", "미친년", "니애미", "느금", "섹스", "야동", "자살", "죽어버려"));
    }

    /** 설정 정리. 쿨다운 0~3600초, 글자 수 10~100, 표시 2~30초, 입력 대기 10~600초. item 은 namespace:id. */
    static Settings settings(String item, double cooldownSeconds, int maxLength, double showSeconds, boolean crossServer,
                             double promptTimeoutSeconds, boolean chacaNpcFilter, List<String> bannedWords) {
        var d = Settings.DEFAULT;
        String i = item == null ? "" : item.strip().toLowerCase(Locale.ROOT);
        if (!i.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) throw new IllegalArgumentException("megaphone.yml item: namespace:id (예: item:megaphone)");
        double cd = Double.isFinite(cooldownSeconds) ? Math.clamp(cooldownSeconds, 0, 3600) : d.cooldownMs() / 1000.0;
        double show = Double.isFinite(showSeconds) ? Math.clamp(showSeconds, MegaphoneProtocol.MIN_DURATION_MS / 1000.0, MegaphoneProtocol.MAX_DURATION_MS / 1000.0) : d.showMs() / 1000.0;
        double wait = Double.isFinite(promptTimeoutSeconds) ? Math.clamp(promptTimeoutSeconds, 10, 600) : d.promptTimeoutMs() / 1000.0;
        var words = new ArrayList<String>();
        for (String w : bannedWords == null ? d.bannedWords() : bannedWords) { String n = normalize(w); if (!n.isEmpty() && !words.contains(n)) words.add(n); }
        return new Settings(i, Math.round(cd * 1000), Math.clamp(maxLength, 10, MAX_LENGTH), (int) Math.round(show * 1000),
                crossServer, Math.round(wait * 1000), chacaNpcFilter, List.copyOf(words));
    }

    /** ChacaNPC TextFilter.normalize 와 같은 규칙: 소문자, 띄어쓰기·기호 제거 (띄어 쓰거나 점을 찍어 피하는 것 막기). */
    static String normalize(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{Punct}·…~]+", "");
    }

    /** 금칙어(정규화된 목록)가 들어 있으면 true. */
    static boolean banned(String text, Collection<String> normalizedWords) {
        String n = normalize(text);
        if (n.isEmpty()) return false;
        for (String w : normalizedWords) if (!w.isEmpty() && n.contains(w)) return true;
        return false;
    }

    /** 문장 다듬기: /공지 와 같은 규칙(색 코드·제어 문자 제거, 공백 하나로) 후 최대 글자 수(code point)로 자른다. */
    static String clean(String raw, int max) { return cutCodePoints(ConvenienceRules.clean(raw, max * 2), max).strip(); }

    /** 앞에서 max 개 code point 까지 (서로게이트 쌍을 반으로 자르지 않음). */
    static String cutCodePoints(String s, int max) {
        if (s == null) return "";
        if (s.codePointCount(0, s.length()) <= max) return s;
        return s.substring(0, s.offsetByCodePoints(0, Math.max(0, max)));
    }

    /** 보낸 사람 이름: 프로토콜 상한(UTF-16 MAX_SENDER) 안으로, 글자를 반으로 자르지 않게. */
    static String senderName(String name) {
        String s = name == null || name.isBlank() ? "?" : name.strip();
        while (s.length() > MegaphoneProtocol.MAX_SENDER) s = s.substring(0, s.offsetByCodePoints(s.length(), -1));
        return s;
    }

    // ------------------------------------------------------------------ 서버 간 메시지

    /** origin = 보낸 서버 플러그인의 이번 실행 토큰(자기 메시지 무시), id = 확성기 한 번의 ID(중복 무시). */
    record Wire(String origin, String id, String server, String sender, String text, int durationMs, byte[] item) {
        Wire { item = item == null ? new byte[0] : item; }
    }

    static byte[] encode(Wire w) {
        try {
            var bytes = new ByteArrayOutputStream(256 + w.item().length);
            var out = new DataOutputStream(bytes);
            out.writeByte(VERSION);
            out.writeUTF(cut(w.origin(), 64)); out.writeUTF(cut(w.id(), MegaphoneProtocol.MAX_ID)); out.writeUTF(cut(w.server(), 48));
            out.writeUTF(cut(w.sender(), MegaphoneProtocol.MAX_SENDER)); out.writeUTF(cut(w.text(), MegaphoneProtocol.MAX_TEXT));
            out.writeInt(w.durationMs());
            out.writeInt(w.item().length); out.write(w.item());
            out.flush();
            byte[] data = bytes.toByteArray();
            if (data.length > MAX_WIRE) throw new IllegalArgumentException("확성기 메시지가 너무 커요");
            return data;
        } catch (IOException impossible) { throw new UncheckedIOException(impossible); }
    }

    /** 모양이 맞지 않으면 null (다른 버전·깨진 메시지는 조용히 버린다). */
    static Wire decode(byte[] data) {
        if (data == null || data.length < 3 || data.length > MAX_WIRE) return null;
        try {
            var in = new DataInputStream(new ByteArrayInputStream(data));
            if (in.readUnsignedByte() != VERSION) return null;
            String origin = in.readUTF(), id = in.readUTF(), server = in.readUTF(), sender = in.readUTF(), text = in.readUTF();
            int duration = in.readInt(), n = in.readInt();
            if (n < 0 || n > MegaphoneProtocol.MAX_ITEM_BYTES) return null;
            byte[] item = in.readNBytes(n);
            if (item.length != n || in.available() != 0) return null;
            if (origin.isEmpty() || id.isEmpty() || id.length() > MegaphoneProtocol.MAX_ID || text.isEmpty()
                    || sender.length() > MegaphoneProtocol.MAX_SENDER || text.length() > MegaphoneProtocol.MAX_TEXT) return null;
            duration = Math.clamp(duration, MegaphoneProtocol.MIN_DURATION_MS, MegaphoneProtocol.MAX_DURATION_MS);
            return new Wire(origin, id, server, sender, text, duration, item);
        } catch (IOException | RuntimeException broken) { return null; }
    }

    /** BungeeCord 채널 "Forward ALL" 요청 (Velocity 도 같은 모양을 받는다). */
    static byte[] forwardAll(byte[] payload) {
        try {
            var bytes = new ByteArrayOutputStream(payload.length + 48);
            var out = new DataOutputStream(bytes);
            out.writeUTF("Forward");
            out.writeUTF("ALL");
            out.writeUTF(SUBCHANNEL);
            out.writeShort(payload.length);
            out.write(payload);
            out.flush();
            return bytes.toByteArray();
        } catch (IOException impossible) { throw new UncheckedIOException(impossible); }
    }

    /** 다른 서버에서 Forward 로 받은 메시지 → 확성기 하위 채널이면 안쪽 바이트, 아니면 null. */
    static byte[] unwrapForward(byte[] message) {
        if (message == null || message.length < 4) return null;
        try {
            var in = new DataInputStream(new ByteArrayInputStream(message));
            if (!SUBCHANNEL.equals(in.readUTF())) return null;
            int length = in.readUnsignedShort();
            if (length <= 0 || length > MAX_WIRE) return null;
            byte[] payload = new byte[length];
            in.readFully(payload);
            return payload;
        } catch (IOException | RuntimeException other) { return null; }
    }

    private static String cut(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) : s;
    }

    /** 쿨다운 안내: 남은 초(올림, 최소 1). */
    static long secondsLeft(long leftMs) { return Math.max(1, (leftMs + 999) / 1000); }
}
