package school.magiccodex.paper;

import java.util.*;

/**
 * 별풍선 후원 보상 규칙 (오너 2026-10-26, FINAL_PATCHES §43). 순수 로직 (Bukkit 없음, JUnit 대상).
 *
 * <p>개수(별풍선 개수)가 표(tiers)의 개수와 정확히 같으면 그 상자, 즉사 개수(death.count)면 즉사 + 전원 상자,
 * 그 밖의 개수는 마일리지 += 개수 (mileage-fallback: false 면 보상 없음). 0 이하 개수는 보상 없음.
 */
final class BalloonRules {
    private BalloonRules() {}

    /** 1회 상자 개수 상한 (우편 9첨부 × 99, 너무 큰 값 방지). */
    static final int MAX_BOXES = 99;
    /** 마일리지 한 번 상한 (MileageProtocol.MAX_BALANCE 보다 작게: 잘못 들어온 큰 숫자 방지). */
    static final long MAX_MILEAGE = 10_000_000L;

    /** 상자 보상: ItemsAdder id · 개수 · 보일 이름. */
    record Box(String item, int amount, String name) {
        String text() { return name + " " + amount + "개"; }
    }

    enum Kind { BOX, DEATH, MILEAGE, NONE }

    /** 한 후원의 보상. box = BOX·DEATH 일 때 상자 (DEATH 는 전원에게 줄 상자), mileage = MILEAGE 일 때 값. */
    record Reward(Kind kind, long count, Box box, long mileage) {
        String text() {
            return switch (kind) {
                case BOX -> box.text();
                case DEATH -> "즉사 + 접속자 전원 " + box.text();
                case MILEAGE -> "마일리지 " + mileage;
                case NONE -> "없음";
            };
        }
    }

    /** 문구 묶음 (donation.yml messages·death). */
    record Messages(String cardTitle, String box, String mileage, String death, String failed, String mailTitle, String mailBody,
                    String deathTitle, String announce, String deathCardTitle, String deathMailTitle, String deathMailBody) {
        static final Messages DEFAULT = new Messages("별풍선 후원",
                "[API] {count}개 후원으로 {reward}{이가} 우편함에 지급되었습니다.",
                "[API] {count}개 후원으로 {reward}{이가} 지급되었습니다.",
                "[API] {count}개 후원으로 사망했습니다. 접속 중인 모두에게 {reward}{이가} 우편함에 지급됩니다.",
                "[API] {count}개 후원 보상을 지급하지 못했어요. 운영자에게 알려 주세요 (기록은 남아 있어요).",
                "별풍선 후원 보상", "{donor} 님의 별풍선 {count}개 후원 보상: {reward}",
                "☠", "[{nickname}] 님이 {count}개 후원으로 사망하였습니다!", "별풍선 후원",
                "후원 기념 보상", "{announce}\n접속 중이던 모두에게 드리는 기념 보상: {reward}");
    }

    record Settings(boolean enabled, Map<Long, Box> tiers, long deathCount, Box deathBox, boolean mileageFallback, Messages messages) {}

    static final Settings DEFAULT = new Settings(true, defaultTiers(), 10_000, new Box("item:api_box_premium", 1, "고급마법상자"), true, Messages.DEFAULT);

    private static Map<Long, Box> defaultTiers() {
        var m = new LinkedHashMap<Long, Box>();
        m.put(100L, new Box("item:api_box_normal", 1, "마법상자"));
        m.put(1000L, new Box("item:api_box_normal", 11, "마법상자"));
        m.put(500L, new Box("item:api_box_premium", 1, "고급마법상자"));
        m.put(5000L, new Box("item:api_box_premium", 11, "고급마법상자"));
        return Collections.unmodifiableMap(m);
    }

    /** ItemsAdder id 정리 (namespace:id, 소문자). 틀리면 IllegalArgumentException (한글 문구). */
    static Box box(String item, int amount, String name, String where) {
        String id = item == null ? "" : item.strip().toLowerCase(Locale.ROOT);
        if (!id.matches("[a-z0-9_.\\-]+:[a-z0-9_./\\-]+")) throw new IllegalArgumentException(where + " item 은 ItemsAdder id (예: item:api_box_normal): " + item);
        if (amount < 1 || amount > MAX_BOXES) throw new IllegalArgumentException(where + " amount 는 1~" + MAX_BOXES + ": " + amount);
        String n = name == null || name.isBlank() ? id.substring(id.indexOf(':') + 1) : name.strip();
        return new Box(id, amount, n.length() > 32 ? n.substring(0, 32) : n);
    }

    /** 표 키(개수 글자) → 숫자. 1 이상만. */
    static long count(String key, String where) {
        try { long v = Long.parseLong(key.strip().replace(",", "").replace("_", "")); if (v >= 1) return v; }
        catch (NumberFormatException ignored) {}
        throw new IllegalArgumentException(where + " 의 개수는 1 이상의 숫자: " + key);
    }

    static Settings settings(boolean enabled, Map<Long, Box> tiers, long deathCount, Box deathBox, boolean mileageFallback, Messages messages) {
        if (deathCount < 0) throw new IllegalArgumentException("death.count 는 0(끄기) 이상");
        if (deathCount > 0 && tiers.containsKey(deathCount)) throw new IllegalArgumentException("death.count " + deathCount + " 가 tiers 에도 있어요 (하나만)");
        return new Settings(enabled, Collections.unmodifiableMap(new LinkedHashMap<>(tiers)), deathCount, deathBox, mileageFallback, messages);
    }

    /** 개수 → 보상 (정확히 같은 개수만 표 보상). */
    static Reward reward(Settings s, long count) {
        if (count <= 0) return new Reward(Kind.NONE, count, null, 0);
        if (s.deathCount() > 0 && count == s.deathCount()) return new Reward(Kind.DEATH, count, s.deathBox(), 0);
        var box = s.tiers().get(count);
        if (box != null) return new Reward(Kind.BOX, count, box, 0);
        if (!s.mileageFallback() || count > MAX_MILEAGE) return new Reward(Kind.NONE, count, null, 0);
        return new Reward(Kind.MILEAGE, count, null, count);
    }

    /** 앞 낱말의 끝소리에 맞는 "이"/"가" (한글 받침 · 숫자 읽기). 모르면 "이(가)". */
    static String iGa(String word) {
        if (word == null || word.isEmpty()) return "이(가)";
        char c = word.charAt(word.length() - 1);
        if (c >= 0xAC00 && c <= 0xD7A3) return (c - 0xAC00) % 28 == 0 ? "가" : "이";
        if (c >= '0' && c <= '9') {
            // 0 으로 끝나면 십·백·천·만 (모두 받침) → 이. 끝자리: 2이 4사 5오 9구 → 가, 나머지(일 삼 육 칠 팔) → 이
            return switch (c) { case '2', '4', '5', '9' -> "가"; default -> "이"; };
        }
        return "이(가)";
    }

    /** {키} 채우기. {이가} 는 {reward} 값 뒤 조사. 모르는 {키} 는 그대로 둔다. */
    static String fill(String template, Map<String, String> values) {
        if (template == null) return "";
        var v = new HashMap<>(values);
        v.putIfAbsent("이가", iGa(values.getOrDefault("reward", "")));
        var out = new StringBuilder(template.length() + 32);
        int i = 0;
        while (i < template.length()) {
            int open = template.indexOf('{', i);
            if (open < 0) { out.append(template, i, template.length()); break; }
            int close = template.indexOf('}', open + 1);
            if (close < 0) { out.append(template, i, template.length()); break; }
            String key = template.substring(open + 1, close);
            out.append(template, i, open);
            String val = v.get(key);
            out.append(val != null ? val : "{" + key + "}");
            i = close + 1;
        }
        return out.toString();
    }

    /** 우편 첨부 나누기: 최대 스택 단위로 자른 개수 목록 (예: 11개·최대 64 → [11], 11개·최대 1 → [1 × 11]). */
    static List<Integer> stacks(int amount, int maxStack) {
        int cap = Math.max(1, Math.min(99, maxStack));
        var out = new ArrayList<Integer>();
        for (int left = amount; left > 0; left -= cap) out.add(Math.min(left, cap));
        return out;
    }
}
