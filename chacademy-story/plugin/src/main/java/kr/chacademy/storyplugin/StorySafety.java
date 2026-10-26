package kr.chacademy.storyplugin;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
/** 스토리 공통 규칙 (id 형식, 닉네임 정리, 패킷 속도 제한, 원자적 파일 쓰기). Bukkit 없이 테스트할 수 있게 순수 자바로 둔다. */
final class StorySafety {
    /** 컷신·대화 id, 장면 id, 이벤트 이름 공통 규칙 (FORMAT.md "이름 규칙"). 모드·편집기도 같은 정규식을 쓴다. */
    static final Pattern ID = Pattern.compile("[a-z0-9_\\-]{1,64}");
    /** NPC id (호감도 키). ChacaNPC / MagicCodex 와 같은 id 라서 한글도 허용한다. */
    static final Pattern NPC = Pattern.compile("[^\\s,=]{1,48}");
    static final int NICKNAME_MAX = 32;

    static boolean validId(String s) {
        return s != null && ID.matcher(s).matches();
    }

    /**
     * 콘솔 명령어에 넣을 닉네임. 글자·숫자·_·- 만 남기고 {@value #NICKNAME_MAX}자에서 자른다
     * (공백·따옴표·@ 선택자·줄바꿈으로 명령어를 바꿔치기하지 못하게). 남는 게 없으면 fallback.
     */
    static String safeNickname(String raw, String fallback) {
        if (raw == null) return fallback;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raw.length() && sb.codePointCount(0, sb.length()) < NICKNAME_MAX; ) {
            int cp = raw.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isLetter(cp) || Character.isDigit(cp) || cp == '_' || cp == '-') sb.appendCodePoint(cp);
        }
        return sb.length() == 0 ? fallback : sb.toString();
    }

    /** 플레이어별 패킷 속도 제한 (토큰 버킷). 메인 스레드에서만 쓴다. */
    static final class TokenBucket {
        private final double perSecond, burst;
        private double tokens;
        private long lastNanos;
        private boolean started;
        long dropped;

        TokenBucket(double perSecond, double burst) {
            this.perSecond = perSecond;
            this.burst = burst;
            this.tokens = burst;
        }

        boolean tryTake(long nowNanos) {
            if (started) tokens = Math.min(burst, tokens + Math.max(0, nowNanos - lastNanos) / 1e9 * perSecond);
            started = true;
            lastNanos = nowNanos;
            if (tokens < 1) {
                dropped++;
                return false;
            }
            tokens -= 1;
            return true;
        }
    }

    /** Persist claims before effects: crash recovery is at-most-once, not transactional delivery. */
    static boolean once(Set<String> fired, String event, BooleanSupplier persist, Runnable effect) {
        if (!fired.add(event)) return false;
        if (!persist.getAsBoolean()) { fired.remove(event); return false; }
        effect.run(); return true;
    }
    static boolean declaredEvent(String event, Set<String> commands, Set<String> affinity) {
        return event != null && event.matches("[a-z0-9_-]{1,64}")
                && (commands.contains(event) || affinity.contains(event));
    }
    static void atomicWrite(Path target, String contents) throws IOException {
        Files.createDirectories(target.toAbsolutePath().getParent());
        Path temp=Files.createTempFile(target.toAbsolutePath().getParent(), "story-progress-", ".tmp");
        try {
            try (FileChannel channel=FileChannel.open(temp, StandardOpenOption.WRITE)) {
                ByteBuffer bytes=StandardCharsets.UTF_8.encode(contents);
                while (bytes.hasRemaining()) channel.write(bytes);
                channel.force(true);
            }
            Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp); }
    }

    /**
     * Skript 를 쓰던 시절의 명령 (story_ch1.sk · 예전 스토리 명령). Skript 없이 돌아가므로 실행하지 않는다.
     * 운영 config 의 on-finish 에 "스토리이어 …" 가 남아 있어도 진행은 스토리 단계가 맡는다.
     */
    private static final Set<String> LEGACY = Set.of("스토리완료", "스토리이어", "분류시작", "분류결정", "분류응답", "기숙사지정", "스토리초기화", "분류초기화",
            "storydialogue", "cutscene", "스토리대화", "컷신");

    static boolean legacyCommand(String command) {
        String c = command == null ? "" : command.strip();
        if (c.startsWith("/")) c = c.substring(1);
        int sp = c.indexOf(' ');
        String head = (sp < 0 ? c : c.substring(0, sp)).toLowerCase(java.util.Locale.ROOT);
        return LEGACY.contains(head);
    }
}
