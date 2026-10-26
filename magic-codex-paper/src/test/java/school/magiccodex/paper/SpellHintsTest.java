package school.magiccodex.paper;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** 마법별 힌트 파일 spell-hints.yml (2026-10-26): 읽기 · 3성 진행도 문장 · 대체 문장. */
class SpellHintsTest {
    private static Map<String, Double> req(Object... kv) {
        var m = new LinkedHashMap<String, Double>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], ((Number) kv[i + 1]).doubleValue());
        return m;
    }

    private static SpellHints.Condition cond(String text, Map<String, Double> req) {
        return new SpellHints.Condition("light_leap", "magic.learned.light_leap", text, req, false);
    }

    @Test void parsesStarLinesAndSkipsBlank() throws Exception {
        try (var in = getClass().getResourceAsStream("/spell-hints-fixture.yml")) {
            var y = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
            var lines = SpellHints.parse(y);
            assertEquals("그 바람은 무척 빠르고 통통 튀어요…", lines.get("light_leap").get(1));
            assertEquals("통통 튀는 바람 같은 속도로 달려 보면 바람을 이해하게 될 거예요.", lines.get("light_leap").get(2));
            assertEquals("어딘가 따뜻한 곳이에요.", lines.get("only_first").get(1));
            assertNull(lines.get("only_first").get(2));
            assertFalse(lines.containsKey("blank_one"));
            assertFalse(lines.containsKey("missing_spell"));
        }
        assertEquals(Map.of(), SpellHints.parse(new YamlConfiguration()));
    }

    @Test void thirdStarShowsCurrentCount() {
        var c = cond("달리면서 점프한 횟수를 30번 채우기.", req("jump.sprint", 30));
        assertEquals("카드가 또렷하게 속삭여요… 「달리면서 점프한 횟수를 30번 채우기」 현재 준우님은 12회 달성했어요.",
                SpellHints.third(c, "준우", Map.of("jump.sprint", 12.0)));
    }

    @Test void thirdStarScalesToTextUnit() {
        // 1,000블록 = sprint.centimeters 100000
        var c = cond("달리기로 누적 1,000블록 이동하기.", req("sprint.centimeters", 100000));
        assertEquals("카드가 또렷하게 속삭여요… 「달리기로 누적 1,000블록 이동하기」 현재 준우님은 456블록 달성했어요.",
                SpellHints.third(c, "준우", Map.of("sprint.centimeters", 45678.0)));
    }

    @Test void multiRequirementShowsEachPart() {
        var c = cond("좀비 10마리와 스켈레톤 5마리 처치하기.", req("kill.zombie", 10, "kill.skeleton", 5));
        assertEquals("카드가 또렷하게 속삭여요… 「좀비 10마리와 스켈레톤 5마리 처치하기」 현재 준우님은 3/10마리 · 5/5마리 달성했어요.",
                SpellHints.third(c, "준우", Map.of("kill.zombie", 3.0, "kill.skeleton", 7.0)));
    }

    @Test void unmatchedUnitsUseRawValues() {
        var c = cond("밤에 낚시하며 물고기 3마리 낚기.", req("fish.night", 3, "state.circle", 2));
        assertEquals("카드가 또렷하게 속삭여요… 「밤에 낚시하며 물고기 3마리 낚기」 현재 준우님은 1/3 · 2/2 달성했어요.",
                SpellHints.third(c, "준우", Map.of("fish.night", 1.0, "state.circle", 2.0)));
        var single = cond("빛나는 무언가를 모으기.", req("collect.glow", 8));
        assertEquals("카드가 또렷하게 속삭여요… 「빛나는 무언가를 모으기」 현재 준우님은 2 / 8 달성했어요.",
                SpellHints.third(single, "준우", Map.of("collect.glow", 2.0)));
    }

    @Test void missingProgressOrManualKeepsOldEnding() {
        String end = "그러면 새 마법을 깨칠 수 있을 거예요.";
        var c = cond("달리면서 점프한 횟수를 30번 채우기.", req("jump.sprint", 30));
        assertEquals("카드가 또렷하게 속삭여요… 「달리면서 점프한 횟수를 30번 채우기」 " + end, SpellHints.third(c, "준우", null));
        assertTrue(SpellHints.third(c, "준우", Map.of("other.key", 3.0)).endsWith(end));   // 조건 키를 모름
        var manual = new SpellHints.Condition("wind_push", "magic.learned.wind_push", "하르바 교수의 바람 마법 기초 수업 듣기.",
                req("trace.condition.wind_push", 1), true);
        assertEquals("카드가 또렷하게 속삭여요… 「하르바 교수의 바람 마법 기초 수업 듣기」 " + end,
                SpellHints.third(manual, "준우", Map.of("trace.condition.wind_push", 0.0)));
    }

    @Test void conditionsMergeDiscoveriesAndManual() {
        var d = new YamlConfiguration();
        d.set("spells.gale_dash.permission", "magic.learned.gale_dash");
        d.set("spells.gale_dash.condition-text", "달리기로 누적 1,000블록 이동하기.");
        d.set("spells.gale_dash.requirements.sprint.centimeters", 100000);
        d.set("spells.wind_push.condition-text", "옛 문구.");
        d.set("spells.wind_push.requirements.trace.condition.wind_push", 1);
        d.set("spells.no_text.requirements.x", 1);
        var m = new YamlConfiguration();
        m.set("spells.wind_push.permission", "magic.learned.wind_push");
        m.set("spells.wind_push.condition-text", "하르바 교수의 바람 마법 기초 수업 듣기.");
        m.set("spells.wind_push.requirements.trace.condition.wind_push", 1);
        m.set("spells.season_sense.condition-text", "도서관에서 「계절 관측 일지」 책 읽기.");
        m.set("spells.season_sense.requirements.trace.condition.season_sense", 1);
        var list = SpellHints.parseConditions(d, m);
        assertEquals(3, list.size());
        var gale = list.get(0);
        assertEquals("gale_dash", gale.spell());
        assertFalse(gale.manual());
        assertEquals(Map.of("sprint.centimeters", 100000.0), gale.requirements());
        var push = list.get(1);
        assertTrue(push.manual());
        assertEquals("하르바 교수의 바람 마법 기초 수업 듣기.", push.text());
        assertEquals("magic.learned.season_sense", list.get(2).permission());
        assertTrue(list.get(2).manual());
    }

    @Test void secondStarFallsBackToAutoWitness() {
        var c = new SpellHints.Condition("wind_grooming", "magic.learned.wind_grooming", "가위로 양털을 10번 깎기.", req("shear.sheep", 10), false);
        assertEquals(LifeSpells.witness(c.text(), null), LifeSpells.second(null, c, null));
        assertNotNull(LifeSpells.second(null, c, null));
        assertTrue(LifeSpells.second(null, c, "가위, 양").contains("가위, 양"));
    }
}
