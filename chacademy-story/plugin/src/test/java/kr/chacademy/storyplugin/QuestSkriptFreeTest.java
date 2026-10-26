package kr.chacademy.storyplugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Skript 없이 1장을 돌리는 규칙: 대화완료 패턴, 시작명령, 예전 Skript 명령 차단. */
class QuestSkriptFreeTest {
    @Test void sortingResultDialoguesMatchPatternButQuestionsDoNot() {
        String target = "ch1-14r-*|ch1-14m-*|ch1-14f-*";
        assertTrue(QuestDefs.validDialoguePattern(target));
        for (String house : List.of("arkeon", "lumina", "bestiaz", "noxer"))
            for (String k : List.of("r", "m", "f")) assertTrue(QuestDefs.dialogueMatches(target, "ch1-14" + k + "-" + house));
        assertFalse(QuestDefs.dialogueMatches(target, "ch1-14"), "6문항 대화 자체는 배정 완료가 아니다");
        assertFalse(QuestDefs.dialogueMatches(target, "ch1-15"));
        assertTrue(QuestDefs.dialogueMatches("ch1-5", "ch1-5"));
        assertFalse(QuestDefs.dialogueMatches("ch1-5", "ch1-50"));
        assertFalse(QuestDefs.validDialoguePattern("Bad Id"));
        assertFalse(QuestDefs.validDialoguePattern("ch1-14||x"));
    }

    @SuppressWarnings("unchecked")
    @Test void startCommandsRoundTripAndPatternConditionSurvives() {
        List<String> w = new ArrayList<>();
        QuestDefs d = QuestDefs.parse(Map.of("단계", Map.of("ch1-14", Map.of(
                "시작명령", List.of("/기숙사분류 시작 {player}"),
                "조건", List.of(Map.of("종류", "대화완료", "대상", "ch1-14r-*|ch1-14m-*|ch1-14f-*", "개수", 1)),
                "호출", Map.of("종류", "자동", "지연", 0)))), w);
        QuestDefs.Step s = d.get("ch1-14");
        assertEquals(List.of("기숙사분류 시작 {player}"), s.startCommands, "앞의 / 는 뗀다");
        assertEquals("ch1-14r-*|ch1-14m-*|ch1-14f-*", s.conds.get(0).target());
        QuestDefs back = QuestDefs.parse(d.toMap(), w);
        assertEquals(s.startCommands, back.get("ch1-14").startCommands);
        assertEquals(s.conds.get(0).target(), back.get("ch1-14").conds.get(0).target());
        assertTrue(w.isEmpty(), w.toString());
    }

    @Test void badDialoguePatternIsDroppedWithWarning() {
        List<String> w = new ArrayList<>();
        QuestDefs d = QuestDefs.parse(Map.of("단계", Map.of("a", Map.of("조건", List.of(Map.of("종류", "대화완료", "대상", "ch1 14"))))), w);
        assertTrue(d.get("a").conds.isEmpty());
        assertFalse(w.isEmpty());
    }

    @Test void legacySkriptCommandsAreBlockedButJavaCommandsAreNot() {
        for (String c : List.of("스토리이어 junwoo ch1-19 ch1-20", "/스토리완료 junwoo ch1-6", "분류시작 junwoo", "분류결정 junwoo",
                "분류응답 p_00", "storydialogue junwoo ch1-7", "cutscene play junwoo x", "기숙사지정 a b"))
            assertTrue(StorySafety.legacyCommand(c), c);
        for (String c : List.of("기숙사분류 시작 junwoo", "기숙사분류 결정 junwoo p_21", "대화관리 재생 junwoo 대화 ch1-7",
                "아이템관리 낚시장비 bamboo_common 1 junwoo", "테스트 연금술 열기 junwoo", "유저관리 junwoo 칭호 주기 freshman", "lp user a permission set b true"))
            assertFalse(StorySafety.legacyCommand(c), c);
    }
}
