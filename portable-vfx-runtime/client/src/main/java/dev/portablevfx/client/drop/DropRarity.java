package dev.portablevfx.client.drop;

import java.util.*;
import java.util.regex.Pattern;

/**
 * 바닥에 떨어진 아이템의 등급 판정 + 어느 아이템에 VFX 를 켤지 고르는 순수 로직 (Minecraft 의존 없음, 테스트용).
 *
 * <p>등급 = LORE 2번째 줄(인덱스 1)에서 색 코드와 앞뒤 공백을 뺀 글자가 등급 이름 "하나만" 있을 때.
 * 예: "§6전설" → 전설, "전설 무기" → 없음.
 */
public final class DropRarity {
    public enum Grade {
        COMMON("일반", "claude:commondrop/main"),      // 일반
        RARE("고급", "claude:raredrop/main"),          // 고급
        UNIQUE("유일", "claude:uniquedrop/main"),      // 유일
        LEGENDARY("전설", "claude:legendarydrop/main"); // 전설
        public final String label, effectId;
        Grade(String label, String effectId) { this.label = label; this.effectId = effectId; }
    }

    private static final Pattern LEGACY_CODES = Pattern.compile("(?i)§[0-9A-FK-OR]");

    private DropRarity() { }

    /** lorePlain = LORE 줄들의 getString() 결과. 2번째 줄이 없거나 등급 이름 하나가 아니면 null. */
    public static Grade recognize(List<String> lorePlain) {
        if (lorePlain == null || lorePlain.size() < 2) return null;
        String second = lorePlain.get(1);
        if (second == null) return null;
        String plain = LEGACY_CODES.matcher(second).replaceAll("").strip();
        for (Grade grade : Grade.values()) if (grade.label.equals(plain)) return grade;
        return null;
    }

    /** 후보 하나 = 아이템 엔티티 id, 등급, 플레이어와의 거리². */
    public record Candidate(int entityId, Grade grade, double distanceSq) { }

    /**
     * 켜 둘 아이템 고르기: 높은 등급 먼저, 같은 등급이면 가까운 것 먼저, 최대 limit 개.
     * 이미 켜져 있는 것은 같은 조건이면 유지되도록 약간 우선(경계에서 깜빡임 방지).
     */
    public static Set<Integer> select(Collection<Candidate> candidates, Set<Integer> live, int limit) {
        if (limit <= 0 || candidates.isEmpty()) return Set.of();
        List<Candidate> sorted = new ArrayList<>(candidates);
        sorted.sort(Comparator.<Candidate>comparingInt(c -> -c.grade().ordinal())
                .thenComparingDouble(c -> live.contains(c.entityId()) ? c.distanceSq() * 0.8 : c.distanceSq())
                .thenComparingInt(Candidate::entityId));
        Set<Integer> chosen = new LinkedHashSet<>();
        for (Candidate c : sorted) {
            if (chosen.size() >= limit) break;
            chosen.add(c.entityId());
        }
        return chosen;
    }
}
