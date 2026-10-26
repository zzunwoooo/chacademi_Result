package kr.chacademy.storyplugin;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 대기대화 (2026-10-25): 조건 못 채운 채 NPC 를 누르면 띄울 짧은 고정 대화 — 읽기·저장 왕복. */
class QuestWaitingDialogueTest {
    @Test void parsesAndRoundTripsWaitingDialogues() {
        List<String> warnings = new ArrayList<>();
        QuestDefs d = QuestDefs.parse(Map.of("단계", Map.of("ch1-12", Map.of(
                "설명", "물고기를 낚아 보자",
                "대기대화", List.of(Map.of("NPC", 21, "대화", "ch1-12w-ole"), Map.of("NPC", "11", "대화", "ch1-12w-pipi"),
                        Map.of("NPC", 5), Map.of("대화", "x"), Map.of("NPC", 7, "대화", "Bad Id"))))), warnings);
        var s = d.get("ch1-12");
        assertEquals(Map.of(21, "ch1-12w-ole", 11, "ch1-12w-pipi"), s.waiting);
        assertEquals(3, warnings.stream().filter(w -> w.contains("대기대화")).count());
        QuestDefs back = QuestDefs.parse(d.toMap(), new ArrayList<>());
        assertEquals(s.waiting, back.get("ch1-12").waiting);
    }

    @Test void stepWithoutWaitingWritesNoKey() {
        QuestDefs d = QuestDefs.parse(Map.of("단계", Map.of("a", Map.of("설명", "x"))), new ArrayList<>());
        @SuppressWarnings("unchecked") var steps = (Map<String, Map<String, Object>>) d.toMap().get("단계");
        assertFalse(steps.get("a").containsKey("대기대화"));
        assertTrue(d.get("a").waiting.isEmpty());
    }
}
