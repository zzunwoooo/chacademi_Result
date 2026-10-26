package school.magiccodex.paper;

import java.util.Locale;

/** 동행 펫(PetCompanions)의 순수 계산 — 서버 API 없이 테스트한다. */
final class CompanionMath {
    /** codex_player_state.artifact_runtime 앞에 붙는 꺼낸 펫 토큰 ("cpet=&lt;펫ID&gt;;"). */
    static final String TOKEN = "cpet=";

    /**
     * 도감 체력 (오너 2026-10-26): 교화해 보유한 펫 점수가 많을수록 누적으로 오르는 최대 체력.
     * max × (have / full)^curve 를 반올림. full 이 0 이하면 0, have 가 full 을 넘으면 max.
     */
    static int collectionHealth(double have, double full, double max, double curve) {
        if (!(full > 0) || !(have > 0) || !(max > 0)) return 0;
        double ratio = Math.min(1, have / full);
        return (int) Math.round(max * Math.pow(ratio, Math.clamp(curve, 0.05, 3)));
    }

    static String token(String pet) {
        return pet == null || !school.magiccodex.protocol.PetProtocol.validId(pet) ? "" : TOKEN + pet + ";";
    }

    /** runtime 문자열에서 펫 ID (없으면 null). */
    static String pet(String runtime) {
        if (runtime == null || runtime.isEmpty()) return null;
        for (String part : runtime.split(";")) {
            if (!part.startsWith(TOKEN)) continue;
            String id = part.substring(TOKEN.length());
            return school.magiccodex.protocol.PetProtocol.validId(id) ? id : null;
        }
        return null;
    }

    /** runtime 문자열에서 펫 토큰을 뺀 나머지 (아티팩트 쪽으로 넘긴다). */
    static String withoutPet(String runtime) {
        if (runtime == null || runtime.isEmpty() || !runtime.contains(TOKEN)) return runtime == null ? "" : runtime;
        var out = new StringBuilder();
        for (String part : runtime.split(";")) if (!part.isEmpty() && !part.startsWith(TOKEN)) out.append(part).append(';');
        return out.toString();
    }

    /** 펫 토큰 + 아티팩트 runtime, DB 칸(4096자)을 넘으면 아티팩트 쪽을 ';' 단위로 자른다. */
    static String join(String pet, String artifacts, int max) {
        String head = token(pet), rest = artifacts == null ? "" : artifacts;
        if (head.length() + rest.length() <= max) return head + rest;
        int room = max - head.length();
        if (room <= 0) return head.length() <= max ? head : "";
        int cut = rest.lastIndexOf(';', room - 1);
        return head + (cut < 0 ? "" : rest.substring(0, cut + 1));
    }

    static int motion(String text) {
        return switch (text == null ? "" : text.toLowerCase(Locale.ROOT)) {
            case "fly" -> school.magiccodex.protocol.PetCompanionProtocol.FLY;
            case "swim" -> school.magiccodex.protocol.PetCompanionProtocol.SWIM;
            default -> school.magiccodex.protocol.PetCompanionProtocol.GROUND;
        };
    }

    /** 전투 중: 마지막으로 때리거나 맞은 때(combatAt, 없으면 음수)부터 window 밀리초 안. */
    static boolean inCombat(long combatAt, long now, long window) { return combatAt >= 0 && now - combatAt < window; }

    /** 자동 발동 가능: 쿨이 찼고 전투 중. */
    static boolean ready(long nextAt, long combatAt, long now, long window) { return now >= nextAt && inCombat(combatAt, now, window); }

    /** 흡수 하트 N칸 → 목표 흡수량(체력 2N). 이미 더 많으면 지금 값 그대로. */
    static double absorb(double current, double hearts) { return Math.max(current, Math.max(0, hearts) * 2); }

    /** 포션 단계(1 = I) → amplifier (0부터). 0 이하면 -1 (걸지 않음). */
    static int amplifier(double level) { int n = (int) Math.round(level); return n <= 0 ? -1 : Math.min(n, 10) - 1; }

    /** 낙하 피해 감소 비율(0~1) → FALL_DAMAGE_MULTIPLIER MULTIPLY_SCALAR_1 값. */
    static double fall(double ratio) { return -Math.clamp(ratio, 0, 1); }

    /** 펫 배율 (지능·낚시): +비율 → 1+비율, 음수·이상값은 1. */
    static double factor(double bonus) { return Double.isFinite(bonus) && bonus > 0 ? 1 + Math.min(bonus, 10) : 1; }

    private CompanionMath() {}
}
