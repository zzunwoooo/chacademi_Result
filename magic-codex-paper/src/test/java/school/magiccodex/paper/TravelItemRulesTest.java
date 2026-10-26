package school.magiccodex.paper;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

/** 리턴권·텔포권 (오너 2026-10-26) 순수 규칙. */
class TravelItemRulesTest {
    @Test void settingsDefaultsAndCleaning() {
        var s = TravelItemRules.settings("bad id", "", "item:tp", "텔레포트", null, List.of("WILD", "wild2", "잘못"), List.of());
        assertEquals("item:death_return", s.deathItem());
        assertEquals("리턴권", s.deathName());
        assertEquals("item:tp", s.friendItem());
        assertEquals(Set.of("school", "wild1", "wild2"), s.useServers());
        assertEquals(Set.of("wild1", "wild2"), s.deathServers());
        assertTrue(s.listServers().isEmpty());   // 빈 목록 = 아무도 안 나옴
        assertFalse(TravelItemRules.Settings.DEFAULT.usableOn("arena"));
        assertFalse(TravelItemRules.Settings.DEFAULT.recordsDeathOn("arena"));
        assertFalse(TravelItemRules.Settings.DEFAULT.recordsDeathOn("school"));
    }

    @Test void presencePackRoundTrip() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        String packed = TravelItemRules.pack(List.of(new TravelItemRules.Online(a, "world", "차카\t냥", "루미나"), new TravelItemRules.Online(b, "wild", "별빛", "")));
        var back = TravelItemRules.unpack(packed + "깨진줄\n");
        assertEquals(2, back.size());
        assertEquals("차카 냥", back.get(0).name());
        assertEquals("", back.get(1).dormitory());
    }

    @Test void candidatesSkipArenaSelfDeadServersAndDuplicates() {
        long now = 1_000_000;
        UUID me = UUID.randomUUID(), a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID(), d = UUID.randomUUID();
        var rows = List.of(
                new TravelItemRules.Presence("school", now - 1000, List.of(o(me, "나"), o(a, "가나다"))),
                new TravelItemRules.Presence("wild", now - 2000, List.of(o(b, "라마"), o(a, "가나다"))),   // a: 학교 줄이 더 새것
                new TravelItemRules.Presence("arena", now, List.of(o(c, "경기중"))),
                new TravelItemRules.Presence("wild2", now - 60_000, List.of(o(d, "꺼진서버"))));
        var list = TravelItemRules.candidates(rows, TravelItemRules.Settings.DEFAULT, me, now);
        assertEquals(2, list.size());
        assertEquals(a, list.get(0).id());
        assertEquals("school", list.get(0).server());
        assertEquals(b, list.get(1).id());
    }

    @Test void safeY() {
        assertEquals(70, TravelItemRules.safeY(70, -64, 320, 70));
        assertEquals(65, TravelItemRules.safeY(-90, -64, 320, 64));   // 공허에서 죽음 → 그 열 땅 위
        assertTrue(Double.isNaN(TravelItemRules.safeY(-90, -64, 320, -65)));   // 디딜 곳 없음
        assertEquals(319, TravelItemRules.safeY(400, -64, 320, 70));
    }

    private static TravelItemRules.Online o(UUID id, String name) { return new TravelItemRules.Online(id, "world", name, ""); }
}
