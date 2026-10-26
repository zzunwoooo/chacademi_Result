package school.magiccodex.paper;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 운영 기록(#92)·서버 통계(#96) 순수 로직: 대기열 상한·JSON·명령 거르기·통계 누적. */
final class AuditLogicTest {
    @Test void queueKeepsOrderAndDropsMinorFirst() {
        var q = new AuditQueue<String>(16);
        for (int i = 0; i < 8; i++) q.offer("m" + i, true);
        for (int i = 0; i < 8; i++) q.offer("M" + i, false);
        assertEquals(16, q.size());
        assertEquals(1, q.offer("M8", false)); // 꽉 참 → 사소한 것 중 가장 오래된 m0 버림
        var all = q.drain(100);
        assertEquals(16, all.size());
        assertEquals("m1", all.getFirst());
        assertFalse(all.contains("m0"));
        assertEquals("M8", all.getLast());
        assertEquals(1, q.dropped());
    }

    @Test void queueDropsOldestMajorWhenNoMinor() {
        var q = new AuditQueue<Integer>(16);
        for (int i = 0; i < 17; i++) q.offer(i, false);
        var all = q.drain(5);
        assertEquals(List.of(1, 2, 3, 4, 5), all);
        assertEquals(11, q.size());
    }

    @Test void jsonEscapesAndCuts() {
        var m = new LinkedHashMap<String, Object>();
        m.put("이름", "따옴\"표\\줄\n바꿈");
        m.put("수", 3);
        // 값은 한 줄로 정리(줄바꿈 → 공백)한 뒤 따옴표·역슬래시를 이스케이프한다
        assertEquals("{\"이름\":\"따옴\\\"표\\\\줄 바꿈\",\"수\":\"3\"}", AuditText.json(m));
        assertEquals("{}", AuditText.json(Map.of()));
        var big = new LinkedHashMap<String, Object>();
        for (int i = 0; i < 50; i++) big.put("k" + i, "x".repeat(400));
        String j = AuditText.json(big);
        assertTrue(j.length() <= AuditText.DETAIL_MAX + 20);
        assertTrue(j.endsWith("\"_잘림\":\"예\"}"));
    }

    @Test void pairsSkipNullValues() {
        assertEquals(Map.of("a", "1", "c", "x"), AuditText.pairs("a", 1, "b", null, "c", "x", "odd"));
    }

    @Test void clipAndReadOnly() {
        assertEquals("ab…", AuditText.clip("abcdef", 3));
        assertEquals("a b", AuditText.clip(" a\tb\n", 10));
        assertTrue(AuditText.readOnlyCommand("/유저관리 <닉네임> 일일제한 보기"));
        assertTrue(AuditText.readOnlyCommand("/서버관리 기록 검색 <글자>"));
        assertFalse(AuditText.readOnlyCommand("/유저관리 <닉네임> 돈 추가 <값>"));
        assertFalse(AuditText.readOnlyCommand("/유저관리 <닉네임> 기숙사 초기화 확인"));
    }

    @Test void statsMergeDrainRestore() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        AuditStats.drain(null);
        AuditStats.add(a, "시전", 1); AuditStats.add(a, "시전", 2); AuditStats.add(b, "사망", 1); AuditStats.add(a, "x", 0);
        var onlyA = AuditStats.drain(a.toString());
        assertEquals(1, onlyA.size());
        assertEquals(3, onlyA.getFirst().amount());
        AuditStats.restore(onlyA);
        AuditStats.add(a, "시전", 4);
        var rest = AuditStats.drain(null);
        long sum = rest.stream().filter(d -> d.player().equals(a.toString()) && d.key().equals("시전")).mapToLong(AuditStats.Delta::amount).sum();
        assertEquals(7, sum);
        assertEquals(2, rest.size());
        assertEquals(0, AuditStats.pending());
        assertEquals("몹", AuditStats.deathBucket(false, true));
        assertEquals("플레이어", AuditStats.deathBucket(true, true));
    }
}
