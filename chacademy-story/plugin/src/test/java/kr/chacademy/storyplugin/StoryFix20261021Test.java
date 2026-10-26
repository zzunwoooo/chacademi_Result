package kr.chacademy.storyplugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 2026-10-21 수정: 고정 보상 세션 묶기·영수증 (A07), 시작명령-한번·진행 기록 (A13), 컷신 건너뜀 기록 (C10). */
class StoryFix20261021Test {
    @Test void endRewardNeedsVerifiedFinishOfThatDialogue() {
        assertFalse(LocalStoryRewards.bound("ch1-3.end", null, false, null, null, null), "세션·완료 없이 보낸 패킷");
        assertFalse(LocalStoryRewards.bound("ch1-3.end", "ch1-3", true, null, null, null), "열려 있기만 하고 끝나지 않음");
        assertFalse(LocalStoryRewards.bound("ch1-3.end", null, false, "ch1-2", "s20", null), "다른 대화의 완료");
        assertTrue(LocalStoryRewards.bound("ch1-3.end", null, false, "ch1-3", "s20", null));
    }

    @Test void eventRewardNeedsReadyLiveSessionAndKnownEvent() {
        assertFalse(LocalStoryRewards.bound("ch1-2.e_s11", null, false, null, null, null));
        assertFalse(LocalStoryRewards.bound("ch1-2.e_s11", "ch1-2", false, null, null, null), "준비 전 세션");
        assertFalse(LocalStoryRewards.bound("ch1-2.e_s11", "ch1-3", true, null, null, null), "다른 대화 세션");
        assertTrue(LocalStoryRewards.bound("ch1-2.e_s11", "ch1-2", true, null, null, null));
        assertTrue(LocalStoryRewards.bound("ch1-2.e_s11", "ch1-2", true, null, null, Set.of("e_s11")));
        assertFalse(LocalStoryRewards.bound("ch1-2.e_s11", "ch1-2", true, null, null, Set.of("other")), "서버 흐름에 없는 이벤트");
        assertFalse(LocalStoryRewards.bound("op x", "ch1-2", true, "ch1-2", "end", null), "모르는 키");
    }

    @Test void rewardDialoguesAlwaysGetASession() {
        assertTrue(LocalStoryRewards.hasRewards("ch1-2"));
        assertTrue(LocalStoryRewards.hasRewards("ch1-3"));
        assertFalse(LocalStoryRewards.hasRewards("ch1-4"));
    }

    @Test void rewardReceiptSurvivesSaveLoadAndBlocksRepeat() {
        StoryState st = new StoryState();
        UUID id = UUID.randomUUID();
        StoryState.PlayerStory ps = st.of(id);
        ps.rewards.put("ch1-3.end", LocalStoryRewards.PENDING);
        ps.rewards.put("ch1-2.e_s11", LocalStoryRewards.APPLIED);
        assertFalse(ps.empty(), "영수증만 있어도 저장 대상");
        StoryState back = StoryState.fromMap(st.snapshot(), new ArrayList<>());
        StoryState.PlayerStory b = back.players.get(id);
        assertNotNull(b);
        assertEquals(LocalStoryRewards.PENDING, b.rewards.get("ch1-3.end"), "pending 은 불확실 상태로 남고 다시 주지 않는다");
        assertEquals(LocalStoryRewards.APPLIED, b.rewards.get("ch1-2.e_s11"));
        assertTrue(b.resetLedger("ch1-3"), "관리자 초기화로만 지운다");
        assertFalse(b.rewards.containsKey("ch1-3.end"));
        assertTrue(b.rewards.containsKey("ch1-2.e_s11"));
    }

    @Test void unknownRewardKeysOrStatesAreDroppedOnLoad() {
        UUID id = UUID.randomUUID();
        Map<String, Object> root = Map.of("format", 2, "players", Map.of(id.toString(),
                Map.of("rewards", Map.of("op me", "applied", "ch1-3.end", "weird", "ch1-2.e_s11", "failed"))));
        StoryState back = StoryState.fromMap(root, new ArrayList<>());
        assertEquals(Map.of("ch1-2.e_s11", "failed"), back.players.get(id).rewards);
    }

    @Test void startOnceDefaultsFollowGrantLikeCommandsAndRoundTrips() {
        List<String> w = new ArrayList<>();
        QuestDefs d = QuestDefs.parse(Map.of("단계", Map.of(
                "a", Map.of("시작명령", List.of("기숙사분류 시작 {player}")),
                "b", Map.of("시작명령", List.of("유저관리 {player} 칭호 주기 freshman")),
                "c", Map.of("시작명령", List.of("아이템관리 낚시장비 bamboo_common 1 {player}"), "시작명령-한번", false),
                "e", Map.of("시작명령", List.of("기숙사분류 시작 {player}"), "시작명령-한번", true))), w);
        assertFalse(d.get("a").startOnce(), "멱등 명령은 생략하면 반복 가능");
        assertTrue(d.get("b").startOnce(), "칭호 지급은 생략해도 한 번만");
        assertFalse(d.get("c").startOnce(), "명시한 값이 우선");
        assertTrue(d.get("e").startOnce());
        QuestDefs back = QuestDefs.parse(d.toMap(), w);
        assertNull(back.get("a").startOnce, "생략은 생략으로 저장");
        assertEquals(Boolean.FALSE, back.get("c").startOnce);
        assertEquals(Boolean.TRUE, back.get("e").startOnce);
        assertTrue(w.isEmpty(), w.toString());
    }

    @Test void startedRunSubmittedAndSkippedCutsceneArePersisted() {
        QuestProgress q = new QuestProgress();
        UUID id = UUID.randomUUID();
        QuestProgress.P p = q.of(id);
        p.enter("ch1-19");
        p.submitted = true;
        p.startedRun = p.run;
        p.skippedCutscene = "ch1-19:ch1_ashen_night";
        QuestProgress back = QuestProgress.fromMap(q.snapshot(), new ArrayList<>());
        QuestProgress.P b = back.players.get(id);
        assertTrue(b.submitted, "제출 기록은 재시작 뒤에도 남아 다시 걷지 않는다");
        assertEquals(b.run, b.startedRun, "시작명령-한번 은 재시작 뒤에도 같은 회차에 다시 실행하지 않는다");
        assertEquals("ch1-19:ch1_ashen_night", b.skippedCutscene);
        b.enter("ch1-20");
        assertFalse(b.submitted);
        assertNotEquals(b.run, b.startedRun, "새 회차는 다시 실행");
    }
}
