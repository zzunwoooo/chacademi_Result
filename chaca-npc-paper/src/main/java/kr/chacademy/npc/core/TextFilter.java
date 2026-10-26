package kr.chacademy.npc.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 금지어·탈옥 시도·이상한 출력 검사. filter.yml 목록으로 만든다.
 */
public final class TextFilter {

    public enum Verdict { OK, BANNED, JAILBREAK, META, RECIPE, ROMANCE }

    private final List<String> banned;        // 공백 제거 후 포함 검사
    private final List<String> jailbreak;     // 공백 제거 후 포함 검사
    private final List<Pattern> jailbreakRegex;
    private final List<String> metaWords;     // 출력에 나오면 안 되는 단어
    private final List<String> romanceBanned; // 연애 수위 단어
    private final List<String> chatterBanned; // 공개 잡담·저장되는 메모에 추가로 막을 단어

    /** filter.yml 에 chatter-banned 가 없을 때(예전 파일) 쓰는 기본 목록. */
    public static final List<String> DEFAULT_CHATTER_BANNED = List.of(
            "시발", "씨발", "ㅅㅂ", "ㅆㅂ", "병신", "ㅂㅅ", "지랄", "ㅈㄹ", "좆", "존나", "ㅈㄴ", "개새끼", "미친놈", "미친년",
            "니애미", "느금", "섹스", "야동", "자살", "죽어버려",
            "프롬프트", "언어모델", "인공지능", "챗봇", "시스템메시지", "지시사항", "롤플레이", "역할극", "마인크래프트", "마크서버");

    private static final Pattern AI_WORD = Pattern.compile("(?i)(?<![a-z])(ai|gpt|llm|chatgpt|openai)(?![a-z])");
    private static final Pattern NUMBERED = Pattern.compile("(^|\\s)\\d+\\s*[.)]\\s");
    private static final Pattern UNITS = Pattern.compile(
            "\\d+\\s*(?:(?:g|kg|mg|ml|tbsp|tsp)(?![a-z])|그램|큰술|작은술|스푼|숟가락|티스푼|밀리리터)",
            Pattern.CASE_INSENSITIVE);

    public TextFilter(List<String> banned, List<String> jailbreak, List<String> jailbreakRegex,
                      List<String> metaWords, List<String> romanceBanned) {
        this(banned, jailbreak, jailbreakRegex, metaWords, romanceBanned, DEFAULT_CHATTER_BANNED);
    }

    public TextFilter(List<String> banned, List<String> jailbreak, List<String> jailbreakRegex,
                      List<String> metaWords, List<String> romanceBanned, List<String> chatterBanned) {
        this.chatterBanned = normalizeAll(chatterBanned == null ? DEFAULT_CHATTER_BANNED : chatterBanned);
        this.banned = normalizeAll(banned);
        this.jailbreak = normalizeAll(jailbreak);
        this.jailbreakRegex = new ArrayList<>();
        if (jailbreakRegex != null) {
            for (String r : jailbreakRegex) {
                try {
                    this.jailbreakRegex.add(Pattern.compile(r, Pattern.CASE_INSENSITIVE));
                } catch (PatternSyntaxException ignored) {
                    // 잘못된 정규식은 건너뛴다
                }
            }
        }
        this.metaWords = normalizeAll(metaWords);
        this.romanceBanned = normalizeAll(romanceBanned);
    }

    /** 플레이어 입력 검사. */
    public Verdict checkInput(String text) {
        if (text == null) {
            return Verdict.OK;
        }
        String n = normalize(text);
        for (String w : banned) {
            if (!w.isEmpty() && n.contains(w)) {
                return Verdict.BANNED;
            }
        }
        for (String w : jailbreak) {
            if (!w.isEmpty() && n.contains(w)) {
                return Verdict.JAILBREAK;
            }
        }
        for (Pattern p : jailbreakRegex) {
            if (p.matcher(text).find()) {
                return Verdict.JAILBREAK;
            }
        }
        for (String w : romanceBanned) {
            if (!w.isEmpty() && n.contains(w)) {
                return Verdict.ROMANCE;
            }
        }
        return Verdict.OK;
    }

    /** NPC 대사 검사. 통과 못 하면 고정 대사로 바꾼다. */
    public Verdict checkOutput(String line) {
        if (line == null) {
            return Verdict.META;
        }
        String n = normalize(line);
        for (String w : banned) {
            if (!w.isEmpty() && n.contains(w)) {
                return Verdict.BANNED;
            }
        }
        if (AI_WORD.matcher(line).find()) {
            return Verdict.META;
        }
        for (String w : metaWords) {
            if (!w.isEmpty() && n.contains(w)) {
                return Verdict.META;
            }
        }
        for (String w : romanceBanned) {
            if (!w.isEmpty() && n.contains(w)) {
                return Verdict.ROMANCE;
            }
        }
        if (UNITS.matcher(line).find()) {
            return Verdict.RECIPE;
        }
        java.util.regex.Matcher m = NUMBERED.matcher(" " + line);
        int count = 0;
        while (m.find()) {
            count++;
        }
        if (count >= 2) {
            return Verdict.RECIPE;
        }
        return Verdict.OK;
    }

    /** 분위기 노트 같은 짧은 문장 검사 (입력·출력 둘 다 통과해야 함). */
    public boolean isClean(String text) {
        return checkInput(text) == Verdict.OK && checkOutput(text) == Verdict.OK;
    }

    /**
     * 여러 사람에게 보이거나(NPC끼리 잡담) 오래 저장돼 프롬프트에 다시 들어가는 글(메모·약속·분위기 노트) 검사.
     * {@link #isClean} + chatter-banned 목록. 통과 못 하면 그 글은 버린다.
     */
    public boolean isPublicSafe(String text) {
        if (text == null || text.isBlank() || !isClean(text)) {
            return false;
        }
        String n = normalize(text);
        for (String w : chatterBanned) {
            if (!w.isEmpty() && n.contains(w)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 저장하거나 여러 사람에게 보여줄 글 준비: {@link TextSanitizer#clean}(제어문자·색 코드·URL 제거, 길이 제한) 뒤
     * {@link #isPublicSafe} 검사.
     *
     * @return 정리된 글. 비었거나 검사에 걸리면 null (그 글만 버리고 나머지 대답은 그대로 쓴다)
     */
    public String storable(String raw, int max) {
        String clean = raw == null ? null : TextSanitizer.clean(raw, max);
        return clean != null && isPublicSafe(clean) ? clean : null;
    }

    public static String normalize(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{Punct}·…~]+", "");
    }

    private static List<String> normalizeAll(List<String> list) {
        List<String> out = new ArrayList<>();
        if (list != null) {
            for (String s : list) {
                if (s != null) {
                    String n = normalize(s);
                    if (!n.isEmpty()) {
                        out.add(n);
                    }
                }
            }
        }
        return out;
    }
}
