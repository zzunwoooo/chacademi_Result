package kr.chacademy.motionplugin.broom;

import org.bukkit.configuration.ConfigurationSection;

/**
 * 빗자루 한 종류의 조작 수치. brooms.yml 에서 읽는다.
 *
 * <p>2026-10-16: 30종 기획 확정. yml 의 수치를 그대로 쓴다(예전의 등급별 배수는 없앰).
 * 등급은 일반 / 희귀 / 유일 / 전설 네 가지. 스페이스 상승 힘(climb)은 능력치가 아니라 모든 빗자루 공통값
 * (brooms.yml 의 climb-power, 빗자루별 climb 키가 있으면 그것)이다.
 *
 * @param id        모델 파일 이름과 같은 ID (모드의 assets/chacademy_motion/brooms/&lt;id&gt;.bbmodel)
 * @param maxSpeed  최고 속도, 블록/틱 (1.0 = 초당 20블록)
 * @param accel     W 를 누를 때 틱마다 붙는 속도
 * @param turn      느릴 때 틱마다 돌 수 있는 각도(도). 최고 속도에서는 60% 로 줄어든다. 위아래에도 같은 값
 * @param brake     S 를 누를 때 틱마다 줄어드는 속도 비율 (0.03 = 3%)
 * @param stability 균형 게이지가 깎이는 정도를 나누는 값. 클수록 덜 떨어지고 최대 균형도 커진다
 * @param climb     스페이스 상승 힘 (공통값)
 */
public record BroomType(String id, String name, String grade, float maxSpeed, float accel, float turn, float brake,
                        float stability, float climb) {

    static BroomType read(String id, ConfigurationSection s) {
        return read(id, s, 0.055);
    }

    static BroomType read(String id, ConfigurationSection s, double climbPower) {
        return new BroomType(id,
                s.getString("name", id),
                grade(s.getString("grade", "")),
                clamp(s.getDouble("max-speed", 0.8), 0.2, 2.5),
                clamp(s.getDouble("accel", 0.035), 0.005, 0.2),
                clamp(s.getDouble("turn", 10), 1, 30),
                clamp(s.getDouble("brake", 0.035), 0.005, 0.3),
                clamp(s.getDouble("stability", 1.0), 0.3, 3),
                clamp(s.getDouble("climb", climbPower), 0.01, 0.2));
    }

    /** 표시 등급을 네 가지로 맞춘다. 예전 이름(기본·고급)은 일반·희귀로 읽는다. */
    static String grade(String raw) {
        String g = raw == null ? "" : raw;
        if (g.contains("전설")) return "전설";
        if (g.contains("유일")) return "유일";
        if (g.contains("희귀") || g.contains("고급")) return "희귀";
        return "일반";
    }

    private static float clamp(double v, double lo, double hi) {
        return (float) Math.max(lo, Math.min(hi, v));
    }
}
