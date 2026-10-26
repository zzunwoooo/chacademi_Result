package school.magiccodex.paper;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 한글 편의 명령 (오픈 전 메모 #95) 순수 규칙 — Bukkit 없이 시험할 수 있는 부분만.
 * 설정 값 정리 · 할말 다듬기 · 도움 쿨다운 · 서버 간 메시지 모양.
 */
final class ConvenienceRules {
    private ConvenienceRules() {}

    /** 서버 간 메시지(BungeeCord Forward)의 하위 채널 이름. */
    static final String SUBCHANNEL = "ChacaConvenience";
    static final int VERSION = 1;
    /** 서버 간 메시지 하나의 최대 크기 (바이트). 넘으면 보내지 않는다. */
    static final int MAX_WIRE = 4096;

    /** 소리 하나 (빈 key = 소리 없음). */
    record Sound(String key, float volume, float pitch) {
        static final Sound NONE = new Sound("", 0, 1);
        boolean enabled() { return !key.isEmpty() && volume > 0; }
    }

    /** convenience-commands.yml 내용. */
    record Settings(String noticeTitle, Sound noticeSound, boolean crossServer,
                    long helpCooldownMs, int helpMaxLength, Sound helpSound, long helpAckWaitTicks, int helpKeep,
                    String helpSent, String helpNoOperator, String helpCooldown,
                    String trashTitle, int trashRows) {
        static final Settings DEFAULT = new Settings("운영자 공지", new Sound("minecraft:block.note_block.chime", 1f, 1.2f), true,
                60_000, 200, new Sound("minecraft:block.note_block.bell", 1f, 1.4f), 60, 50,
                "운영자에게 보냈어요", "지금 접속한 운영자가 없어요. 기록에 남겨 두었으니 운영자가 들어오면 볼 수 있어요",
                "조금 뒤에 다시 보낼 수 있어요 · {초}초",
                "쓰레기통 · 닫으면 사라져요", 6);
    }

    /** 설정 값 정리. 쿨다운 0~3600초, 글자 수 20~500, 확인 대기 20~200틱, 기록 10~500개, 쓰레기통 1~6줄. */
    static Settings settings(String noticeTitle, Sound noticeSound, boolean crossServer,
                             double helpCooldownSeconds, int helpMaxLength, Sound helpSound, long helpAckWaitTicks, int helpKeep,
                             String helpSent, String helpNoOperator, String helpCooldown, String trashTitle, int trashRows) {
        var d = Settings.DEFAULT;
        double cd = Double.isFinite(helpCooldownSeconds) ? Math.clamp(helpCooldownSeconds, 0, 3600) : d.helpCooldownMs() / 1000.0;
        return new Settings(text(noticeTitle, d.noticeTitle()), noticeSound == null ? Sound.NONE : noticeSound, crossServer,
                Math.round(cd * 1000), Math.clamp(helpMaxLength, 20, 500), helpSound == null ? Sound.NONE : helpSound,
                Math.clamp(helpAckWaitTicks, 20, 200), Math.clamp(helpKeep, 10, 500),
                text(helpSent, d.helpSent()), text(helpNoOperator, d.helpNoOperator()), text(helpCooldown, d.helpCooldown()),
                text(trashTitle, d.trashTitle()), Math.clamp(trashRows, 1, 6));
    }

    /** 소리 설정 정리: key 가 비었거나 "없음"이면 소리 없음. 볼륨 0~4, 높낮이 0.5~2. */
    static Sound sound(String key, double volume, double pitch) {
        if (key == null || key.isBlank() || key.strip().equals("없음")) return Sound.NONE;
        String k = key.strip().toLowerCase(Locale.ROOT);
        if (!k.matches("[a-z0-9_.:/-]{1,100}")) return Sound.NONE;
        return new Sound(k, (float) Math.clamp(Double.isFinite(volume) ? volume : 1, 0, 4), (float) Math.clamp(Double.isFinite(pitch) ? pitch : 1, 0.5, 2));
    }

    private static String text(String s, String fallback) { return s == null || s.isBlank() ? fallback : s.strip(); }

    /**
     * 공지·도움 할말 다듬기: 색 코드(§) 와 제어 문자를 빼고, 공백을 하나로, 앞뒤 공백 제거, 최대 길이로 자른다.
     * 남는 글자가 없으면 빈 문자열.
     */
    static String clean(String raw, int max) {
        if (raw == null) return "";
        var out = new StringBuilder(Math.min(raw.length(), max + 8));
        boolean space = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '§') { i++; continue; }
            if (Character.isWhitespace(c) || Character.isISOControl(c)) { space = out.length() > 0; continue; }
            if (space) { out.append(' '); space = false; }
            out.append(c);
        }
        String s = out.toString();
        if (s.length() > max) s = s.substring(0, max);
        return s.strip();
    }

    /** 도움 쿨다운: 마지막으로 보낸 시각(없으면 음수)부터 남은 밀리초. 0 이면 보낼 수 있음. */
    static long cooldownLeft(long lastAt, long now, long cooldown) {
        if (lastAt < 0 || cooldown <= 0) return 0;
        long left = lastAt + cooldown - now;
        return left > 0 ? Math.min(left, cooldown) : 0;
    }

    /** "{초}" 를 남은 초(올림, 최소 1)로. */
    static String cooldownMessage(String template, long leftMs) {
        return template.replace("{초}", String.valueOf(Math.max(1, (leftMs + 999) / 1000)));
    }

    // ------------------------------------------------------------------ 서버 간 메시지

    /** ANNOUNCE (2026-10-26, 별풍선 후원 사망): who = 알림 카드 제목, account = 화면 가운데 큰 글씨, text = 가운데 작은 글씨·카드 본문. 맨 뒤에만 더한다(순서 = 번호). */
    enum Kind { NOTICE, HELP, HELP_ACK, ANNOUNCE }

    /**
     * 서버 간 메시지. origin = 보낸 서버 플러그인의 이번 실행 토큰(자기 메시지 무시용), id = 메시지 하나의 ID(중복 무시·확인 짝),
     * server = 보낸 서버 이름(travel.yml server-id), who = 보낸 사람 화면 이름, account = 계정 이름, text = 할말.
     */
    record Wire(Kind kind, String origin, String id, String server, String who, String account, String text, long time) {}

    static byte[] encode(Wire w) {
        try {
            var bytes = new ByteArrayOutputStream(256);
            var out = new DataOutputStream(bytes);
            out.writeByte(VERSION);
            out.writeByte(w.kind().ordinal());
            out.writeUTF(nz(w.origin())); out.writeUTF(nz(w.id())); out.writeUTF(nz(w.server()));
            out.writeUTF(nz(w.who())); out.writeUTF(nz(w.account())); out.writeUTF(nz(w.text()));
            out.writeLong(w.time());
            out.flush();
            byte[] data = bytes.toByteArray();
            if (data.length > MAX_WIRE) throw new IllegalArgumentException("메시지가 너무 길어요");
            return data;
        } catch (IOException impossible) { throw new UncheckedIOException(impossible); }
    }

    /** 모양이 맞지 않으면 null (다른 버전·깨진 메시지는 조용히 버린다). */
    static Wire decode(byte[] data) {
        if (data == null || data.length < 3 || data.length > MAX_WIRE) return null;
        try {
            var in = new DataInputStream(new ByteArrayInputStream(data));
            if (in.readUnsignedByte() != VERSION) return null;
            int kind = in.readUnsignedByte();
            if (kind >= Kind.values().length) return null;
            var w = new Wire(Kind.values()[kind], in.readUTF(), in.readUTF(), in.readUTF(), in.readUTF(), in.readUTF(), in.readUTF(), in.readLong());
            if (w.origin().isEmpty() || w.id().isEmpty() || w.id().length() > 64) return null;
            return w;
        } catch (IOException | RuntimeException broken) { return null; }
    }

    /** BungeeCord 채널 "Forward" 요청 (Velocity 도 같은 모양을 받는다): 모든 서버로. */
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

    /** 다른 서버에서 Forward 로 받은 BungeeCord 채널 메시지 → 우리 하위 채널이면 안쪽 바이트, 아니면 null. */
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

    private static String nz(String s) {
        if (s == null) return "";
        // writeUTF 한계(65535 바이트)보다 훨씬 작게. 할말은 이미 clean 으로 잘려 있다.
        return s.length() > 600 ? s.substring(0, 600) : s;
    }

    /** /메모리 한 줄용: 바이트 → "1234MB". */
    static String megabytes(long bytes) { return Math.max(0, bytes / (1024 * 1024)) + "MB"; }

    /** TPS 표시 (소수 한 자리, 20 넘으면 20). */
    static String tps(double tps) {
        if (!Double.isFinite(tps)) return "?";
        return String.format(Locale.ROOT, "%.1f", Math.min(20.0, Math.max(0, tps)));
    }
}
