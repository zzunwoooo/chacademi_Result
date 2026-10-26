package kr.chacademy.storyplugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class QuestDefsTest {
    @SuppressWarnings("unchecked")
    @Test void roundTripKeepsStepsConditionsAndTriggers() {
        QuestDefs d = new QuestDefs();
        d.startStep = "ch1-11";
        d.startAfterDialogue = "ch1-5";
        QuestDefs.Step s = new QuestDefs.Step("ch1-11");
        s.chapter = "1장 · 항구 마을";
        s.description = "준비물을 사서 피피에게 가자.";
        s.conds.add(new QuestDefs.Cond(QuestDefs.CondType.ITEM, "이름:교복", 1, "교복 사기", false, List.of()));
        s.conds.add(new QuestDefs.Cond(QuestDefs.CondType.INTERACT, "블록지정", 3, "", true, List.of("world,1,2,3", "world,4,5,6")));
        s.trigger.type = QuestDefs.TrigType.NPC;
        s.trigger.server = "school";
        s.trigger.npc = 12;
        s.dialogue = "ch1-11";
        s.next = "ch1-12";
        d.steps.put(s.id, s);
        QuestDefs.Step n = new QuestDefs.Step("ch1-12");
        n.trigger.type = QuestDefs.TrigType.LOCATION;
        n.trigger.world = "world";
        n.trigger.x = 10.5; n.trigger.y = 64; n.trigger.z = -3.5; n.trigger.radius = 0.2;
        d.steps.put(n.id, n);

        List<String> warnings = new ArrayList<>();
        QuestDefs back = QuestDefs.parse(d.toMap(), warnings);
        assertEquals("ch1-11", back.startStep);
        assertEquals("ch1-5", back.startAfterDialogue);
        QuestDefs.Step b = back.get("ch1-11");
        assertEquals("1장 · 항구 마을", b.chapter);
        assertEquals(2, b.conds.size());
        assertEquals(QuestDefs.CondType.ITEM, b.conds.get(0).type());
        assertEquals("교복 사기", b.conds.get(0).display());
        assertTrue(b.conds.get(1).hidden());
        assertEquals(2, b.conds.get(1).required(), "상호작용은 지정한 블록 수까지만 요구");
        assertEquals(QuestDefs.TrigType.NPC, b.trigger.type);
        assertEquals(12, b.trigger.npc);
        assertEquals("school", b.trigger.server);
        assertEquals(QuestDefs.MIN_RADIUS, back.get("ch1-12").trigger.radius, 1e-9, "반경은 최소값으로");
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test void badIdsAndMissingNextAreWarned() {
        List<String> warnings = new ArrayList<>();
        QuestDefs d = QuestDefs.parse(Map.of("단계", Map.of("Bad Id", Map.of(), "a", Map.of("다음", "zzz", "호출", Map.of("종류", "뭔가")))), warnings);
        assertNull(d.get("Bad Id"));
        assertEquals(QuestDefs.TrigType.NONE, d.get("a").trigger.type);
        assertTrue(warnings.size() >= 3, warnings.toString());
    }

    @Test void progressRoundTrip() {
        QuestProgress q = new QuestProgress();
        UUID id = UUID.randomUUID();
        QuestProgress.P p = q.of(id);
        p.enter("ch1-19");
        p.counts.put(0, 2);
        p.hits.computeIfAbsent(1, k -> new java.util.LinkedHashSet<>()).add("world,1,2,3");
        p.submitted = true;
        p.phase = QuestProgress.Phase.PLAYING;
        p.waiting = "c:ch1_ashen_night";
        p.finished("ch1-18");
        QuestProgress back = QuestProgress.fromMap(q.snapshot(), new ArrayList<>());
        QuestProgress.P b = back.players.get(id);
        assertEquals("ch1-19", b.step);
        assertEquals(1, b.run);
        assertEquals(2, b.counts.get(0));
        assertTrue(b.hits.get(1).contains("world,1,2,3"));
        assertTrue(b.submitted);
        assertEquals(QuestProgress.Phase.PLAYING, b.phase);
        assertEquals("c:ch1_ashen_night", b.waiting);
        assertEquals(List.of("ch1-18"), b.history);
        b.enter("ch1-20");
        assertEquals(2, b.run);
        assertTrue(b.counts.isEmpty());
        assertFalse(b.submitted);
        assertEquals(QuestProgress.Phase.ACTIVE, b.phase);
    }

    /** 2026-10-26: 단계별 서버·서버이동 (1장 tutorial → 배 → school). */
    @Test void stepServerAndTransferRoundTrip() {
        List<String> warnings = new ArrayList<>();
        QuestDefs d = QuestDefs.parse(Map.of("단계", Map.of(
                "ch1-7", Map.of("서버", "tutorial", "다음", "ch1-8"),
                "ch1-8", Map.of("서버", "school", "서버이동", true),
                "ch1-9", Map.of("서버이동", true))), warnings);
        assertEquals("tutorial", d.get("ch1-7").server);
        assertFalse(d.get("ch1-7").transfer);
        assertEquals("school", d.get("ch1-8").server);
        assertTrue(d.get("ch1-8").transfer);
        assertTrue(warnings.stream().anyMatch(w -> w.startsWith("ch1-9: 서버이동")), "서버 없이 서버이동만 있으면 경고");
        QuestDefs back = QuestDefs.parse(d.toMap(), new ArrayList<>());
        assertEquals("school", back.get("ch1-8").server);
        assertTrue(back.get("ch1-8").transfer);
        assertEquals("", back.get("ch1-9").server);
        assertFalse(d.toMap().toString().contains("서버이동=false"));
    }
}
