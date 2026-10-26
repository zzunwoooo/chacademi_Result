package kr.chacademy.npc.core;

import java.util.Collection;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * AI·플레이어에게서 온 글자를 저장하거나 다른 사람에게 보여주기 전에 정리한다. Bukkit 의존성 없음.
 * <ul>
 *   <li>{@link #stripUnsafe} — 대사용: 제어문자·§ 색 코드·보이지 않는 서식 문자만 지운다.</li>
 *   <li>{@link #clean} — 메모·약속·분위기 노트·잡담용: 위 + &amp; 색 코드·URL 제거, 길이 제한.</li>
 *   <li>{@link #publicRumor} — 공개 잡담에 쓰는 소문 문장. 사건 종류만 보고 서버가 만든다 (플레이어 이름·원문 없음).</li>
 *   <li>{@link #privateRumor} — 본인과의 대화 프롬프트에 쓰는 소문 문장. 자유 글이 섞인 사건은 원문 대신 서버 문장.</li>
 * </ul>
 */
public final class TextSanitizer {

    private TextSanitizer() {
    }

    private static final Pattern SECTION = Pattern.compile("§[0-9a-fk-orxA-FK-ORX]?");
    private static final Pattern AMP_HEX = Pattern.compile("&#[0-9a-fA-F]{6}");
    private static final Pattern AMP_CODE = Pattern.compile("(?<![0-9A-Za-z])&[0-9a-fk-orA-FK-OR](?=\\S)"); // "R&D팀" 같은 평범한 글은 건드리지 않는다
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cc}\\u2028\\u2029]");
    private static final Pattern FORMAT = Pattern.compile("\\p{Cf}");
    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final Pattern URL = Pattern.compile(
            "(?i)(?:https?://|www\\.)\\S+"
                    + "|(?<![a-z0-9@])[a-z0-9][a-z0-9-]{0,62}(?:\\.[a-z0-9-]{1,63})*"
                    + "\\.(?:com|net|org|kr|gg|io|tv|me|xyz|co|link|site|shop|app|dev)(?![a-z0-9])(?:/\\S*)?");

    /** 대사용 최소 정리: 제어문자는 공백으로, § 색 코드와 보이지 않는 서식 문자는 삭제. null → null. */
    public static String stripUnsafe(String s) {
        if (s == null) {
            return null;
        }
        String out = SECTION.matcher(s).replaceAll("");
        out = CONTROL.matcher(out).replaceAll(" ");
        out = FORMAT.matcher(out).replaceAll("");
        return out;
    }

    /** {@link #stripUnsafe} + &amp; 색 코드·URL 제거 + 공백 정리. 비면 "". */
    public static String strip(String s) {
        if (s == null) {
            return "";
        }
        String out = stripUnsafe(s);
        out = AMP_HEX.matcher(out).replaceAll("");
        out = AMP_CODE.matcher(out).replaceAll("");
        out = URL.matcher(out).replaceAll(" ");
        return SPACES.matcher(out).replaceAll(" ").trim();
    }

    /**
     * 저장용 정리: {@link #strip} + 길이 제한(코드포인트).
     *
     * @return 정리된 글, 남는 게 없으면 null
     */
    public static String clean(String s, int max) {
        String out = strip(s);
        if (out.isEmpty() || out.equalsIgnoreCase("null")) {
            return null;
        }
        if (max > 0 && out.codePointCount(0, out.length()) > max) {
            out = out.substring(0, out.offsetByCodePoints(0, max)).trim();
        }
        return out.isEmpty() ? null : out;
    }

    /** 글에 이 이름들 중 하나라도 들어 있는지 (대소문자 무시, 2글자 미만은 건너뜀). */
    public static boolean mentionsAny(String s, Collection<String> names) {
        if (s == null || names == null) {
            return false;
        }
        String lower = s.toLowerCase(Locale.ROOT);
        for (String n : names) {
            if (n != null && n.strip().length() >= 2 && lower.contains(n.strip().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 공개 잡담(근처 모든 플레이어가 봄)에 넣는 소문 문장. 사건 종류와 NPC 이름만 쓴다.
     * 플레이어 이름·호칭·AI/플레이어가 쓴 원문은 절대 들어가지 않는다.
     */
    public static String publicRumor(String type, String npcName) {
        String npc = npcName == null || npcName.isBlank() ? "누군가" : strip(npcName);
        if (npc.isEmpty()) {
            npc = "누군가";
        }
        return switch (type == null ? "" : type) {
            case "promise" -> npc + josa(npc, "이", "가") + " 어떤 학생이랑 특별한 약속을 했다더라";
            case "quest_accept" -> "어떤 학생이 " + npc + "의 부탁을 들어주기로 했다더라";
            case "quest_done" -> "어떤 학생이 " + npc + "의 부탁을 해결해 줬다더라";
            case "gift", "gift_romance" -> "어떤 학생이 " + npc + "에게 선물을 줬다더라";
            case "heart" -> npc + josa(npc, "이", "가") + " 요즘 어떤 학생이랑 부쩍 가까워졌다더라";
            case "ending" -> npc + "에게 특별한 사람이 생겼다더라";
            case "nickname" -> npc + josa(npc, "이", "가") + " 어떤 학생을 별명으로 부른다더라";
            default -> npc + "에게 요즘 무슨 일이 있었다더라";
        };
    }

    /**
     * 내용이 서버가 만든 정해진 문장인 사건 종류. 그 밖(promise·nickname·모르는 종류)은 AI·플레이어가 쓴 글이
     * 섞여 있을 수 있어, 소문으로 옮길 때 원문을 쓰지 않는다.
     */
    public static boolean isStructuredType(String type) {
        if (type == null) {
            return false;
        }
        return switch (type) {
            case "quest_accept", "quest_done", "gift", "gift_romance", "heart", "ending" -> true;
            default -> false;
        };
    }

    /**
     * 그 플레이어 본인과의 1:1 대화 프롬프트에 넣는 소문 문장 (다른 NPC가 전해 들은 이야기).
     * 서버가 만든 사건(선물·퀘스트·하트 등)은 저장된 문장을 정리해서 그대로 쓰고,
     * 자유 글이 섞인 사건(promise·nickname·모르는 종류)은 원문 대신 서버가 만든 문장으로 바꾼다.
     */
    public static String privateRumor(String type, String npcName, String storedText) {
        if (isStructuredType(type)) {
            String clean = clean(storedText, 200);
            if (clean != null) {
                return clean;
            }
        }
        String npc = npcName == null ? "" : strip(npcName);
        if (npc.isEmpty()) {
            npc = "누군가";
        }
        return switch (type == null ? "" : type) {
            case "promise" -> "이 학생이 " + npc + josa(npc, "과", "와") + " 특별한 약속을 했다더라";
            case "nickname" -> npc + josa(npc, "이", "가") + " 이 학생을 별명으로 부른다더라";
            case "quest_accept" -> "이 학생이 " + npc + "의 부탁을 들어주기로 했다더라";
            case "quest_done" -> "이 학생이 " + npc + "의 부탁을 해결해 줬다더라";
            case "gift", "gift_romance" -> "이 학생이 " + npc + "에게 선물을 줬다더라";
            case "heart" -> "이 학생이 요즘 " + npc + josa(npc, "과", "와") + " 부쩍 가까워졌다더라";
            case "ending" -> "이 학생이 " + npc + "의 특별한 사람이 됐다더라";
            default -> "이 학생과 " + npc + " 사이에 요즘 무슨 일이 있었다더라";
        };
    }

    /** 받침 유무에 맞는 조사. 한글로 끝나지 않으면 받침 없는 쪽. */
    public static String josa(String word, String withFinal, String withoutFinal) {
        if (word == null || word.isEmpty()) {
            return withoutFinal;
        }
        char c = word.charAt(word.length() - 1);
        if (c >= 0xAC00 && c <= 0xD7A3) {
            return (c - 0xAC00) % 28 != 0 ? withFinal : withoutFinal;
        }
        return withoutFinal;
    }
}
