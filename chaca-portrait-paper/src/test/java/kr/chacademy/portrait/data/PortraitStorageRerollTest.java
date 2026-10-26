package kr.chacademy.portrait.data;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/** 다시 그리기 상태 전이: 생성 시작 전 실패는 반환, 시작 후 실패는 소모. (SQLite) */
class PortraitStorageRerollTest {

    @TempDir
    Path dir;

    private interface Body {
        void run(PortraitStorage s) throws Exception;
    }

    private void with(Body body) throws Exception {
        var db = new Database(dir, Logger.getAnonymousLogger());
        try {
            db.call(() -> {
                var s = new PortraitStorage(db);
                s.init();
                s.init(); // 다시 켜도 (컬럼 추가 포함) 문제 없어야 한다
                body.run(s);
                return null;
            }).get();
        } finally {
            db.close();
        }
    }

    private static String pending(PortraitStorage s, UUID id, String token, String server) throws Exception {
        s.insertReroll(token, id, server, new byte[]{1}, "smile");
        assertTrue(s.moveReroll(token, PortraitStorage.R_PREPARED, PortraitStorage.R_PENDING));
        return token;
    }

    private static String state(PortraitStorage s, UUID id, String token) throws Exception {
        for (PortraitStorage.Reroll r : s.recentRerolls(id, 50)) {
            if (r.token().equals(token)) {
                return r.state();
            }
        }
        return null;
    }

    @Test
    void failureBeforeStartIsRefundedAndDoesNotCountAsAttempt() throws Exception {
        with(s -> {
            UUID id = UUID.randomUUID();
            String token = pending(s, id, "before", "school");
            // 작업 실패 경로 (RerollService.failed): PENDING → REFUND_DUE + 사유
            assertTrue(s.moveReroll(token, PortraitStorage.R_PENDING, PortraitStorage.R_REFUND, "예산 소진"));
            assertFalse(s.consumeReroll(token, "late"), "반환 대상은 소모 처리되지 않는다");
            assertFalse(s.markRerollStarted(token, id, "2026-10-11"), "반환 대상은 시작할 수 없다");
            List<PortraitStorage.Reroll> due = s.refundsDue(id, "school");
            assertEquals(1, due.size());
            assertEquals(PortraitStorage.R_REFUND, due.get(0).state());
            assertEquals("예산 소진", due.get(0).error());
            assertTrue(s.refundsDue(id, "wild").isEmpty(), "차감한 서버에서만 반환");
            assertArrayEquals(new long[]{0, 0}, s.rerollUsage(id, "2026-10-11"), "쿨다운·횟수에 넣지 않음");
            assertTrue(s.moveReroll(token, PortraitStorage.R_REFUND, PortraitStorage.R_REFUNDED));
            assertFalse(s.moveReroll(token, PortraitStorage.R_REFUND, PortraitStorage.R_REFUNDED), "반환은 한 번만");
            assertEquals(0, s.takeConsumedNotices(id));
        });
    }

    @Test
    void failureAfterStartIsConsumedAndCountsAsAttempt() throws Exception {
        with(s -> {
            UUID id = UUID.randomUUID();
            String token = pending(s, id, "after", "school");
            assertTrue(s.markRerollStarted(token, id, "2026-10-11"));
            assertFalse(s.markRerollStarted(token, id, "2026-10-11"), "시작 경계는 한 번만");
            assertEquals(PortraitStorage.R_STARTED, state(s, id, token));
            long[] usage = s.rerollUsage(id, "2026-10-11");
            assertTrue(usage[0] > 0, "쿨다운 시각 기록");
            assertEquals(1, usage[1], "시도 횟수에 넣음");
            assertEquals(0, s.rerollUsage(id, "2026-10-12")[1]);
            // 시작된 뒤에는 어떤 반환 경로로도 가지 않는다
            assertFalse(s.moveReroll(token, PortraitStorage.R_PENDING, PortraitStorage.R_REFUND, "x"));
            assertEquals(0, s.failPendingRerolls("school"));
            assertTrue(s.refundsDue(id, "school").isEmpty());
            assertTrue(s.consumeReroll(token, "OpenAI 500: boom"));
            assertFalse(s.consumeReroll(token, "again"), "소모 처리는 한 번만");
            assertEquals(PortraitStorage.R_CONSUMED, state(s, id, token));
            assertEquals("OpenAI 500: boom", s.recentRerolls(id, 5).get(0).error());
            assertTrue(s.refundsDue(id, "school").isEmpty(), "소모된 것은 반환 대상이 아님");
            // 접속해 있지 않았다면 다음 접속 때 한 번만 안내
            assertEquals(1, s.takeConsumedNotices(id));
            assertEquals(0, s.takeConsumedNotices(id));
            // 소모된 기록에는 결과를 저장할 수 없다
            long generation = s.claimGeneration(id, "school", 60000);
            assertFalse(s.commitResult(id, token, "sha", new byte[]{2}, "model", "reroll", "", 0, "2026-10-11", "school", generation));
            assertNull(s.sha(id));
        });
    }

    @Test
    void successRequiresStartedStateAndKeepsTheAttemptCount() throws Exception {
        with(s -> {
            UUID id = UUID.randomUUID();
            String token = pending(s, id, "ok", "school");
            long generation = s.claimGeneration(id, "school", 60000);
            assertFalse(s.commitResult(id, token, "early", new byte[]{2}, "model", "reroll", "", 0, "2026-10-11", "school", generation),
                    "시작 경계를 넘지 않은 기록은 확정할 수 없다");
            assertTrue(s.markRerollStarted(token, id, "2026-10-11"));
            assertTrue(s.commitResult(id, token, "sha", new byte[]{2}, "model", "reroll", "", 0, "2026-10-11", "school", generation));
            assertEquals(PortraitStorage.R_DONE, state(s, id, token));
            assertEquals("sha", s.sha(id));
            assertFalse(s.consumeReroll(token, "late failure"), "성공한 기록은 소모 실패로 바뀌지 않는다");
            assertEquals(1, s.rerollUsage(id, "2026-10-11")[1], "성공도 시도 1번");
            // 같은 날 두 번째 시도, 다음 날 첫 시도
            assertTrue(s.markRerollStarted(pending(s, id, "second", "school"), id, "2026-10-11"));
            assertEquals(2, s.rerollUsage(id, "2026-10-11")[1]);
            assertTrue(s.markRerollStarted(pending(s, id, "nextday", "school"), id, "2026-10-12"));
            assertEquals(1, s.rerollUsage(id, "2026-10-12")[1]);
        });
    }

    @Test
    void restartRefundsPendingAndConsumesStartedOnThisServerOnly() throws Exception {
        with(s -> {
            UUID id = UUID.randomUUID();
            pending(s, id, "p-school", "school");
            pending(s, id, "p-wild", "wild");
            assertTrue(s.markRerollStarted(pending(s, id, "s-school", "school"), id, "2026-10-11"));
            assertTrue(s.markRerollStarted(pending(s, id, "s-wild", "wild"), id, "2026-10-11"));
            assertEquals(1, s.failPendingRerolls("school"));
            assertEquals(1, s.consumeStartedRerolls("school"));
            assertEquals(0, s.failPendingRerolls("school"));
            assertEquals(0, s.consumeStartedRerolls("school"));
            assertEquals(PortraitStorage.R_REFUND, state(s, id, "p-school"));
            assertEquals(PortraitStorage.R_CONSUMED, state(s, id, "s-school"));
            assertEquals(PortraitStorage.R_PENDING, state(s, id, "p-wild"), "다른 서버의 기록은 건드리지 않는다");
            assertEquals(PortraitStorage.R_STARTED, state(s, id, "s-wild"));
            assertEquals(1, s.takeConsumedNotices(id), "재시작으로 소모된 것도 다음 접속 때 안내");
        });
    }

    @Test
    void adminRefundMovesOneConsumedRowBackToRefundDue() throws Exception {
        with(s -> {
            UUID id = UUID.randomUUID();
            assertNull(s.refundConsumed(id, "school"));
            String token = pending(s, id, "consumed", "school");
            assertTrue(s.markRerollStarted(token, id, "2026-10-11"));
            assertTrue(s.consumeReroll(token, "safety"));
            assertNull(s.refundConsumed(id, "wild"), "차감한 서버에서만");
            assertEquals(token, s.refundConsumed(id, "school"));
            assertNull(s.refundConsumed(id, "school"), "한 건은 한 번만");
            List<PortraitStorage.Reroll> due = s.refundsDue(id, "school");
            assertEquals(1, due.size());
            assertEquals(token, due.get(0).token());
            assertEquals("safety", due.get(0).error(), "실패 사유는 남는다");
        });
    }

    @Test
    void budgetAdjustmentIsClampedAtZeroAndShasAreBatched() throws Exception {
        with(s -> {
            assertEquals(500, s.adjustSpent(500).spent());
            assertEquals(200, s.adjustSpent(-300).spent());
            assertEquals(0, s.adjustSpent(-1_000_000).spent());
            assertTrue(s.reserve("r1", "school", 100, 1000));
            assertEquals(100, s.adjustSpent(0).reserved(), "예약 중 금액은 건드리지 않는다");
            UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
            s.savePortrait(a, "sha-a", new byte[]{1}, "model", "admin", "", 0);
            s.savePortrait(b, "sha-b", new byte[]{2}, "model", "admin", "", 0);
            var found = s.shas(List.of(a, b, c));
            assertEquals(2, found.size());
            assertEquals("sha-a", found.get(a));
            assertEquals("sha-b", found.get(b));
            assertTrue(s.shas(List.of()).isEmpty());
        });
    }
}
