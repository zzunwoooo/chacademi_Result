package school.chacademia.magicmythic;

/**
 * Which spell (and element) the code on the server thread is working for right now. Set by Mech while a mechanic
 * runs and restored by Ticker around every running bolt/zone/companion, so damage/heal hooks can attribute results
 * (acquisition conditions such as "pebble_toss kills" or "light magic undead kills").
 */
final class SpellTrace {
    private SpellTrace() {}
    /** pvpScale: 전투 스펙 2026-10-26 — 플레이어 대상 타격에 곱할 PvP 배율 (Bridge 변수 pvp_scale). -1 = 모름 (MagicArmor fallback). */
    record Now(String spell, String element, double pvpScale) {
        static final Now NONE = new Now("", "", -1);
        Now(String spell, String element) { this(spell, element, -1); }
    }
    private static Now current = Now.NONE;

    static Now current() { return current; }
    static Now set(Now now) { Now before = current; current = now == null ? Now.NONE : now; return before; }
    static Now of(Params p) { return p == null ? Now.NONE : new Now(p.s("spell", ""), p.s("element", "")); }
}
