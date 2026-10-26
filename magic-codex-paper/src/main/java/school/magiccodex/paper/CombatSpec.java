package school.magiccodex.paper;

/**
 * 전투 스펙 (오너 승인 2026-10-26, docs/plans/combat-spec.md) 중 MagicCodexBridge 쪽 순수 계산.
 * 마법 방어·PvP 피해 적용은 MagicMythic(MagicArmor / Targets / MagicProtection)이 한다.
 */
final class CombatSpec {
    private CombatSpec() {}
    /** PvP 에서 아티팩트 피해 보정 합의 상한 (+20%). 줄이는 보정(은둔자 등불 파티 -20% 등)은 그대로 둔다. */
    static final double PVP_ARTIFACT_CAP = 1.20;

    static double artifactMultiplier(double mult, boolean pvp) {
        if (!Double.isFinite(mult) || mult < 0) return 1;
        return pvp ? Math.min(mult, PVP_ARTIFACT_CAP) : mult;
    }

    /** PvP 마법 피해 = pvp-base + pvp-ratio × 지능 (spell-runtime.yml, 생성기 tools/spells.py: k × (40 + 지능) × 마법별 상대 배율). */
    static double pvpDamage(double pvpBase, double pvpRatio, double power) {
        return Math.clamp(pvpBase + pvpRatio * Math.clamp(power, 0, 1_000_000), 0, 1_000_000);
    }

    /**
     * 미몹 스킬에 넘기는 PvP 배율 (변수 pvp_scale) = PvP 피해 ÷ 몹 상대 피해. 같은 시전의 모든 타격(mult·flat·다단)에 같이 곱해져
     * 마법 안의 타격 비율은 그대로 두고 크기만 PvP 값이 된다. PvP 값이 없거나 몹 상대 피해가 0이면 -1 (MagicMythic 기본 배율).
     */
    static double pvpScale(double damage, double pvpBase, double pvpRatio, double power) {
        if (pvpBase < 0 || pvpRatio < 0 || !(damage > 0)) return -1;
        return Math.clamp(pvpDamage(pvpBase, pvpRatio, power) / damage, 0, 10);
    }
}
