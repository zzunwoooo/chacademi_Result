package school.magiccodex.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import school.magiccodex.database.DatabaseSettings;

class AffinityStoreTest {
    @TempDir Path dir;
    private final UUID p = UUID.randomUUID();
    private static final DatabaseSettings SQLITE = new DatabaseSettings(false, "", "", "");

    @Test void dailyCapAndHeartGate() throws Exception {
        try (var s = new AffinityStore(SQLITE, dir.resolve("affinity.db"))) {
            var r = s.add(p, "ella", "chat", 2, 3, "2026-10-05", 1);
            assertEquals(2, r.applied());
            r = s.add(p, "ella", "chat", 2, 3, "2026-10-05", 2);
            assertEquals(1, r.applied(), "하루 한도 3을 넘지 않음");
            r = s.add(p, "ella", "chat", 2, 3, "2026-10-05", 3);
            assertEquals(0, r.applied());
            r = s.add(p, "ella", "chat", 2, 3, "2026-10-06", 4);
            assertEquals(2, r.applied(), "다음 날은 다시 오름");
            r = s.add(p, "ella", "quest", 50, 0, "2026-10-06", 5);
            assertEquals(30, r.row().score(), "퀘스트 0단계 상한 30");
            assertEquals(25, r.applied());
            s.setHeart(p, "ella", 1, 6);
            r = s.add(p, "ella", "quest", 50, 0, "2026-10-06", 7);
            assertEquals(60, r.row().score(), "퀘스트 1단계 상한 60");
            s.setHeart(p, "ella", 0, 8);
            assertEquals(1, s.load(p, "ella").heart(), "하트 단계는 내려가지 않음");
            r = s.add(p, "ella", "strike", -3, 3, "2026-10-06", 9);
            assertEquals(57, r.row().score(), "음수는 한도 없이 적용");
            assertEquals(57, s.loadAll(p).get("ella").score());
        }
    }

    @Test void giftIsOnceOnlyAndRecoverable() throws Exception {
        try (var s = new AffinityStore(SQLITE, dir.resolve("affinity.db"))) {
            String t1 = UUID.randomUUID().toString();
            assertEquals(AffinityStore.GiftBegin.OK, s.beginGift(t1, p, "mina", 6, "2026-10-05", 1, 10, "A"));
            assertEquals(AffinityStore.GiftBegin.DUPLICATE, s.beginGift(t1, p, "mina", 6, "2026-10-05", 1, 11, "A"));
            String t2 = UUID.randomUUID().toString();
            assertEquals(AffinityStore.GiftBegin.CAP, s.beginGift(t2, p, "mina", 6, "2026-10-05", 1, 12, "A"), "하루 1번");
            assertEquals(6, s.commitGift(t1, 13).row().score());
            assertNull(s.commitGift(t1, 14), "같은 토큰 두 번 확정 안 됨");
            assertEquals(6, s.load(p, "mina").score());

            String t3 = UUID.randomUUID().toString();
            assertEquals(AffinityStore.GiftBegin.OK, s.beginGift(t3, p, "mina", 6, "2026-10-06", 1, 20, "A"));
            assertTrue(s.cancelGift(t3));
            assertFalse(s.cancelGift(t3));
            assertNull(s.commitGift(t3, 21), "취소된 선물은 확정 안 됨");
            String t4 = UUID.randomUUID().toString();
            assertEquals(AffinityStore.GiftBegin.OK, s.beginGift(t4, p, "mina", 6, "2026-10-06", 1, 22, "A"), "취소하면 횟수 돌려받음");
            // A08 (2026-10-21): 시간만 지난 pending 은 자동 확정되지 않는다 (영수증으로만 정함)
            assertEquals(6, s.load(p, "mina").score());
            var pending = s.pendingGifts(p, 100);
            assertEquals(1, pending.size());
            assertEquals("A", pending.getFirst().origin());
            assertTrue(s.pendingGifts(p, 22).isEmpty(), "지금 진행 중인(이후에 시작한) 선물은 건드리지 않음");
            assertEquals(0, s.legacyPendingGifts());
            // 영수증이 있으면 확정 (한 번만), 다시 해도 바뀌지 않음
            assertEquals(12, s.commitGift(t4, 30).row().score());
            assertNull(s.commitGift(t4, 31));
            assertEquals(12, s.load(p, "mina").score());
        }
    }

    @Test void giftRecoveryPolicy() {
        assertEquals(AffinityStore.Recovery.CANCEL, AffinityStore.recovery("A", "A", false, false), "영수증 없음 → 취소");
        assertEquals(AffinityStore.Recovery.COMMIT, AffinityStore.recovery("A", "A", true, true), "영수증 저장됨 → 확정");
        assertEquals(AffinityStore.Recovery.WAIT, AffinityStore.recovery("A", "A", true, false), "메모리에만 → 기다림");
        assertEquals(AffinityStore.Recovery.SKIP, AffinityStore.recovery("B", "A", true, true), "다른 서버 → 건드리지 않음");
        assertEquals(AffinityStore.Recovery.REVIEW, AffinityStore.recovery("", "A", true, true), "옛 기록 → 관리자");
    }

    @Test void receiptlessGiftCancelsAndReturnsDailyCount() throws Exception {
        try (var s = new AffinityStore(SQLITE, dir.resolve("affinity.db"))) {
            String t = UUID.randomUUID().toString();
            assertEquals(AffinityStore.GiftBegin.OK, s.beginGift(t, p, "ella", 5, "2026-10-21", 1, 10, "A"));
            // 차감 전에 멈춤(영수증 없음) → 접속 해결 = 취소
            assertEquals(AffinityStore.Recovery.CANCEL, AffinityStore.recovery(s.pendingGifts(p, 100).getFirst().origin(), "A", false, false));
            assertTrue(s.cancelGift(t));
            assertEquals(0, s.load(p, "ella").score());
            assertEquals(AffinityStore.GiftBegin.OK, s.beginGift(UUID.randomUUID().toString(), p, "ella", 5, "2026-10-21", 1, 20, "A"), "같은 날 다시 선물 가능");
        }
    }

    @Test void giftRefusedAtGateWithoutUsingDailyCount() throws Exception {
        try (var s = new AffinityStore(SQLITE, dir.resolve("affinity.db"))) {
            s.add(p, "luka", "quest", 50, 0, "2026-10-05", 1); // 상한 30
            assertEquals(AffinityStore.GiftBegin.FULL, s.beginGift(UUID.randomUUID().toString(), p, "luka", 10, "2026-10-05", 1, 2, "A"));
            assertEquals(AffinityStore.GiftBegin.FULL, s.beginGift(UUID.randomUUID().toString(), p, "luka", -1, "2026-10-05", 1, 3, "A"), "싫은 선물도 받지 않음");
            s.setHeart(p, "luka", 1, 4); // 퀘스트 1 완료 → 상한 60
            assertEquals(AffinityStore.GiftBegin.OK, s.beginGift(UUID.randomUUID().toString(), p, "luka", 10, "2026-10-05", 1, 5, "A"), "횟수를 안 썼으니 같은 날 줄 수 있음");
        }
    }

    @Test void nicknameStored() throws Exception {
        try (var s = new AffinityStore(SQLITE, dir.resolve("affinity.db"))) {
            assertEquals("책벌레", s.setNickname(p, "ella", "책벌레", 1).nickname());
            assertEquals("책벌레", s.load(p, "ella").nickname());
            assertEquals(30, AffinityStore.gate(0));
            assertEquals(60, AffinityStore.gate(1));
            assertEquals(90, AffinityStore.gate(2));
            assertEquals(100, AffinityStore.gate(3));
            assertEquals(100, AffinityStore.gate(5));
            assertEquals(100, AffinityStore.gate(Integer.MAX_VALUE));
            assertEquals(30, AffinityStore.gate(Integer.MIN_VALUE));
        }
    }

    @Test void adminScorePreservesHeartNicknameAndBounds() throws Exception {
        try (var s = new AffinityStore(SQLITE, dir.resolve("affinity.db"))) {
            assertEquals(30, s.adminChange(p, "ella", "set", 100, 1).score());
            s.setHeart(p, "ella", 2, 2);
            s.setNickname(p, "ella", "책벌레", 3);
            assertEquals(new AffinityStore.Row(90, 2, "책벌레"),
                    s.adminChange(p, "ella", "add", Integer.MAX_VALUE, 4));
            assertEquals(new AffinityStore.Row(0, 2, "책벌레"),
                    s.adminChange(p, "ella", "remove", Integer.MAX_VALUE, 5));
            assertEquals(17, s.adminChange(p, "ella", "set", 17, 6).score());
            assertEquals(22, s.adminChange(p, "ella", "add", 5, 7).score());
            assertEquals(15, s.adminChange(p, "ella", "remove", 7, 8).score());
            assertEquals(15, s.adminChange(p, "ella", "add", 0, 9).score());
            assertEquals(new AffinityStore.Row(15, 2, "책벌레"), s.load(p, "ella"));
            s.setHeart(p, "ella", 5, 10);
            assertEquals(100, s.adminChange(p, "ella", "set", Integer.MAX_VALUE, 11).score());
        }
    }

    @Test void adminChangesNeitherConsumeNorResetDailyCaps() throws Exception {
        try (var s = new AffinityStore(SQLITE, dir.resolve("affinity.db"))) {
            String day = "2026-10-06";
            s.add(p, "ella", "chat", 2, 3, day, 1);
            assertEquals(12, s.adminChange(p, "ella", "add", 10, 2).score());
            assertEquals(1, s.add(p, "ella", "chat", 2, 3, day, 3).applied());
            s.adminChange(p, "ella", "set", 0, 4);
            assertEquals(0, s.add(p, "ella", "chat", 2, 3, day, 5).applied(), "관리자 변경은 사용한 한도를 복구하지 않음");
            s.adminChange(p, "ella", "add", 10, 6);
            s.adminChange(p, "ella", "remove", 10, 7);
            assertEquals(0, s.add(p, "ella", "chat", 2, 3, day, 8).applied());
            assertEquals(3, s.add(p, "ella", "quest", 3, 3, day, 9).applied(), "다른 출처 한도에도 영향 없음");
        }
    }

    @Test void invalidAdminChangesDoNotCreateRows() throws Exception {
        try (var s = new AffinityStore(SQLITE, dir.resolve("affinity.db"))) {
            assertThrows(IllegalArgumentException.class, () -> s.adminChange(p, "ella", "set", -1, 1));
            assertThrows(IllegalArgumentException.class, () -> s.adminChange(p, "ella", "unknown", 1, 1));
            assertTrue(s.loadAll(p).isEmpty());
        }
    }

    @Test void adminChangesFromTwoConnectionsDoNotLoseUpdates() throws Exception {
        Path file = dir.resolve("affinity.db");
        try (var first = new AffinityStore(SQLITE, file);
             var second = new AffinityStore(SQLITE, file);
             var workers = Executors.newFixedThreadPool(2)) {
            first.setHeart(p, "ella", 5, 1);
            first.setNickname(p, "ella", "책벌레", 2);
            CountDownLatch start = new CountDownLatch(1);
            var one = workers.submit(() -> {
                start.await();
                for (int i = 0; i < 20; i++) first.adminChange(p, "ella", "add", 1, i + 3);
                return null;
            });
            var two = workers.submit(() -> {
                start.await();
                for (int i = 0; i < 20; i++) second.adminChange(p, "ella", "add", 1, i + 3);
                return null;
            });
            start.countDown();
            one.get(20, TimeUnit.SECONDS);
            two.get(20, TimeUnit.SECONDS);
            assertEquals(new AffinityStore.Row(40, 5, "책벌레"), first.load(p, "ella"));
        }
    }

    @Test void adminTargetsMustBeRegisteredAndUnambiguous() {
        Map<String, String> names = Map.of("ella", "엘라", "mina", "미나");
        assertEquals("ella", NpcSocialService.registeredNpcId(names, "엘라"));
        assertEquals("ella", NpcSocialService.registeredNpcId(names, "ELLA"));
        assertEquals("mina", NpcSocialService.registeredNpcId(names, "mina"));
        assertThrows(IllegalArgumentException.class, () -> NpcSocialService.registeredNpcId(names, "arbitrary-db-id"));
        assertThrows(IllegalArgumentException.class, () -> NpcSocialService.registeredNpcId(names, "엘"));
        assertThrows(IllegalArgumentException.class, () -> NpcSocialService.registeredNpcId(names, null));
        assertThrows(IllegalArgumentException.class, () -> NpcSocialService.registeredNpcId(Map.of(), "ella"));
        Map<String, String> duplicates = Map.of("ella", "엘라", "ella_2", "엘라");
        assertThrows(IllegalArgumentException.class, () -> NpcSocialService.registeredNpcId(duplicates, "엘라"));
        assertEquals("ella_2", NpcSocialService.registeredNpcId(duplicates, "ella_2"));
    }

    @Test void chatRewardCountsPerNpcPerDayAndStopsAtGate() throws Exception {
        Path db = dir.resolve("affinity.db");
        try (var s = new AffinityStore(SQLITE, db)) {
            for (int i = 0; i < 3; i++) assertEquals(1, s.addCounted(p, "ella", "chat_reward", 1, 3, "2026-10-25", i).applied());
            assertEquals(0, s.addCounted(p, "ella", "chat_reward", 1, 3, "2026-10-25", 4).applied(), "NPC 하나당 하루 3번");
            assertEquals(1, s.addCounted(p, "mina", "chat_reward", 1, 3, "2026-10-25", 5).applied(), "다른 NPC 는 따로 셈");
            assertEquals(3, s.usedToday(p, "ella", "chat_reward", "2026-10-25"));
            assertEquals(1, s.add(p, "ella", "chat", 1, 3, "2026-10-25", 6).applied(), "mood(chat) 한도와 따로 셈");
        }
        // 다시 연 저장소(서버 이동·재시작과 같음)에서도 같은 날 횟수 그대로
        try (var s = new AffinityStore(SQLITE, db)) {
            assertEquals(0, s.addCounted(p, "ella", "chat_reward", 1, 3, "2026-10-25", 7).applied(), "재접속으로 초기화되지 않음");
            assertEquals(1, s.addCounted(p, "ella", "chat_reward", 1, 3, "2026-10-26", 8).applied(), "다음 날 다시");
            s.add(p, "luka", "quest", 30, 0, "2026-10-26", 9); // 상한 30
            assertEquals(0, s.addCounted(p, "luka", "chat_reward", 1, 3, "2026-10-26", 10).applied());
            assertEquals(0, s.usedToday(p, "luka", "chat_reward", "2026-10-26"), "상한이면 횟수도 안 씀");
            s.setHeart(p, "luka", 1, 11);
            assertEquals(1, s.addCounted(p, "luka", "chat_reward", 1, 3, "2026-10-26", 12).applied(), "퀘스트 완료 후 재개");
            s.add(p, "mina", "quest", 28, 0, "2026-10-26", 13); // 29
            var r = s.addCounted(p, "mina", "chat_reward", 5, 3, "2026-10-26", 14);
            assertEquals(1, r.applied(), "상한까지 남은 만큼만");
            assertEquals(30, r.row().score());
        }
    }

    @Test void gateQuestLookup() {
        assertEquals(1, NpcSocialService.gateLevel("30"));
        assertEquals(3, NpcSocialService.gateLevel("90"));
        assertEquals(0, NpcSocialService.gateLevel("100"));
        Map<Integer, String> q = Map.of(30, "q30", 60, "q60");
        assertEquals("q30", NpcSocialService.gateQuest(q, new AffinityStore.Row(30, 0, "")));
        assertNull(NpcSocialService.gateQuest(q, new AffinityStore.Row(29, 0, "")), "상한 전");
        assertNull(NpcSocialService.gateQuest(q, new AffinityStore.Row(30, 1, "")), "이미 깸 (상한 60)");
        assertEquals("q60", NpcSocialService.gateQuest(q, new AffinityStore.Row(60, 1, "")));
        assertNull(NpcSocialService.gateQuest(q, new AffinityStore.Row(90, 2, "")), "90 설정 없음 → 아무 일 없음");
        assertNull(NpcSocialService.gateQuest(null, new AffinityStore.Row(30, 0, "")), "설정 비어 있음");
        assertNull(NpcSocialService.gateQuest(q, new AffinityStore.Row(100, 3, "")));
    }

    /** #100: 상한 퀘스트를 이미 깼는데 하트가 안 오른 채 상한에 갇혔으면 다시 주지 않고 다음 상한을 연다. */
    @Test void gateQuestAlreadyCompletedUnlocksInsteadOfReoffering() {
        assertEquals(NpcSocialService.GateAction.UNLOCK, NpcSocialService.gateAction(true, false), "일회성 의뢰 완료 → 제안 불가지만 상한은 열어야 함");
        assertEquals(NpcSocialService.GateAction.UNLOCK, NpcSocialService.gateAction(true, true), "일일 의뢰로 적었어도 깬 적 있으면 다시 주지 않음");
        assertEquals(NpcSocialService.GateAction.OFFER, NpcSocialService.gateAction(false, true));
        assertEquals(NpcSocialService.GateAction.NONE, NpcSocialService.gateAction(false, false), "진행 중·권한 없음");
    }

    /** #100: 상한 해제 뒤에는 막혔던 선물이 같은 날 바로 다시 들어가고, 오른 만큼만 새 상한까지 오른다. */
    @Test void giftResumesAfterGateQuestAndClampsToNextGate() throws Exception {
        try (var s = new AffinityStore(SQLITE, dir.resolve("affinity.db"))) {
            s.add(p, "mira", "quest", 60, 0, "2026-10-26", 1);
            assertEquals(30, s.load(p, "mira").score());
            assertEquals(AffinityStore.GiftBegin.FULL, s.beginGift("t1", p, "mira", 15, "2026-10-26", 1, 2, "A"));
            s.setHeart(p, "mira", 1, 3);
            assertEquals(AffinityStore.GiftBegin.OK, s.beginGift("t2", p, "mira", 40, "2026-10-26", 1, 4, "A"), "하루 1번인데 막혔던 건 횟수를 안 썼음");
            var r = s.commitGift("t2", 5);
            assertEquals(60, r.row().score(), "새 상한 60에서 멈춤");
            assertEquals(30, r.applied());
            assertEquals(AffinityStore.GiftBegin.FULL, s.beginGift("t3", p, "mira", 1, "2026-10-26", 1, 6, "A"), "60 상한: 다음 퀘스트 전에는 다시 받지 않음");
        }
    }
}
