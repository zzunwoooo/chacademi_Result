package school.chacademia.magicmythic;

import org.bukkit.configuration.ConfigurationSection;

/**
 * 전투 스펙 (오너 승인 2026-10-26, docs/plans/combat-spec.md §3·§5) — 플레이어가 플레이어를 마법으로 맞힐 때의 계산. 순수 로직.
 *  - PvP 피해: 미몹 스킬 변수 pvp_scale(MagicCodexBridge: PvP 피해 ÷ 몹 상대 피해)을 그 시전의 모든 타격에 곱한다. 없으면 fallbackScale.
 *  - 마법 방어 = min(cap, (방어력 + 보호 EPF 합(최대 20)) × perPoint). 방어 강도는 무시. 바닐라 방어·보호 인첸트 대신 이 값만 쓴다.
 *  - 저항 효과는 단계당 20% 그대로, 합해서 resistanceCap 까지 (마법 방어 뒤에 곱함).
 *  - 처형(크로스 애로우)은 감소 0 (방어·저항 모두 무시).
 * 플레이어가 몹을 맞히는 마법은 바꾸지 않는다. 몹이 플레이어에게 쓰는 마법은 #109(몹 스펙)부터 같은 마법 방어(mobMagicArmor)만 적용.
 */
final class MagicArmor {
    /** mobMagicArmor: 몹 스펙 #109 (2026-10-26) — 몹이 플레이어에게 쓰는 마법(몹 스킬)도 같은 마법 방어 (PvP 배율·무적 시간 초기화는 없음). */
    record Settings(boolean enabled, double cap, double perPoint, int epfMax, double resistanceCap, double fallbackScale, boolean resetInvulnerability, boolean mobMagicArmor) {
        static final Settings DEFAULT = new Settings(true, 0.45, 0.0125, 20, 0.20, 0.13, true, true);
        static Settings load(ConfigurationSection c) {
            if (c == null) return DEFAULT;
            return new Settings(c.getBoolean("enabled", true),
                    Math.clamp(c.getDouble("magic-armor-cap", DEFAULT.cap), 0, 0.95),
                    Math.clamp(c.getDouble("magic-armor-per-point", DEFAULT.perPoint), 0, 0.1),
                    Math.clamp(c.getInt("protection-epf-max", DEFAULT.epfMax), 0, 64),
                    Math.clamp(c.getDouble("resistance-cap", DEFAULT.resistanceCap), 0, 1),
                    Math.clamp(c.getDouble("fallback-scale", DEFAULT.fallbackScale), 0, 10),
                    c.getBoolean("reset-invulnerability", true),
                    c.getBoolean("mob-magic-armor", true));
        }
    }
    static volatile Settings current = Settings.DEFAULT;

    private MagicArmor() {}

    /** 마법 방어 감소율 0..cap. armor = 방어력 속성 값, epf = 갑옷 4칸 보호(Protection) 레벨 합. */
    static double reduction(Settings s, double armor, int epf) {
        double a = Double.isFinite(armor) ? Math.max(0, armor) : 0;
        int e = Math.clamp(epf, 0, s.epfMax());
        return Math.clamp((a + e) * s.perPoint(), 0, s.cap());
    }

    /** 저항 효과 감소율: 단계(amplifier+1) × 20%, resistanceCap 까지. level 0 = 없음. */
    static double resistance(Settings s, int level) {
        return Math.clamp(0.2 * Math.max(0, level), 0, s.resistanceCap());
    }

    /** 플레이어 대상 PvP 배율. 시전이 넘긴 pvp_scale 이 0 이상이면 그 값, 아니면(-1 · 없음) fallback. */
    static double pvpScale(Settings s, double traced) {
        return Double.isFinite(traced) && traced >= 0 ? Math.min(traced, 10) : s.fallbackScale();
    }

    /** 감소 후 피해 (흡수 하트 전). */
    static double reduced(double amount, double reduction, double resistance) {
        return Math.max(0, amount) * (1 - Math.clamp(reduction, 0, 1)) * (1 - Math.clamp(resistance, 0, 1));
    }
}
