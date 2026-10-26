package school.chacademi.ops;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * 비밀번호 하나 + 세션 쿠키 (오너: "이 정도면 충분").
 * 틀린 횟수는 IP 마다 센다: max-failures 번 틀리면 lock 동안 그 IP 로그인 거부. 전체로도 10분에 50번 넘게 틀리면 잠깐 막는다.
 */
final class Auth {
    enum Result { OK, WRONG, LOCKED }

    private record Failures(int count, long firstAt, long lockedUntil) { }

    private final byte[] expected;
    private final long sessionMillis;
    private final int maxFailures;
    private final long lockMillis;
    private final LongSupplier clock;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Long> sessions = new ConcurrentHashMap<>();
    private final Map<String, Failures> failures = new ConcurrentHashMap<>();
    private long globalWindowStart;
    private int globalFailures;

    Auth(String passwordSha256, long sessionMillis, int maxFailures, long lockMillis, LongSupplier clock) {
        this.expected = HexFormat.of().parseHex(passwordSha256);
        this.sessionMillis = sessionMillis;
        this.maxFailures = maxFailures;
        this.lockMillis = lockMillis;
        this.clock = clock;
    }

    static String sha256Hex(String text) {
        return HexFormat.of().formatHex(sha256(text));
    }

    private static byte[] sha256(String text) {
        try { return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    /** 성공하면 새 세션 토큰을 token[0] 에 넣는다. */
    synchronized Result login(String ip, String password, String[] token) {
        long now = clock.getAsLong();
        Failures f = failures.get(ip);
        if (f != null && f.lockedUntil() > now) return Result.LOCKED;
        if (now - globalWindowStart > 600_000) { globalWindowStart = now; globalFailures = 0; }
        if (globalFailures >= 50) return Result.LOCKED;
        boolean ok = password != null && password.length() <= 256 && MessageDigest.isEqual(expected, sha256(password));
        if (!ok) {
            globalFailures++;
            int count = (f == null || now - f.firstAt() > lockMillis) ? 1 : f.count() + 1;
            long first = (f == null || now - f.firstAt() > lockMillis) ? now : f.firstAt();
            failures.put(ip, new Failures(count, first, count >= maxFailures ? now + lockMillis : 0));
            return count >= maxFailures ? Result.LOCKED : Result.WRONG;
        }
        failures.remove(ip);
        byte[] b = new byte[32];
        random.nextBytes(b);
        String t = HexFormat.of().formatHex(b);
        sessions.put(t, now + sessionMillis);
        sessions.entrySet().removeIf(e -> e.getValue() < now);
        token[0] = t;
        return Result.OK;
    }

    boolean valid(String token) {
        if (token == null || !token.matches("[0-9a-f]{64}")) return false;
        Long until = sessions.get(token);
        if (until == null) return false;
        if (until < clock.getAsLong()) { sessions.remove(token); return false; }
        return true;
    }

    void logout(String token) {
        if (token != null) sessions.remove(token);
    }

    /** 남은 잠금 초 (0 = 안 잠김). */
    long lockedSeconds(String ip) {
        Failures f = failures.get(ip);
        long now = clock.getAsLong();
        return f == null || f.lockedUntil() <= now ? 0 : (f.lockedUntil() - now + 999) / 1000;
    }
}
