package school.chacademia.housing;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** A03 (2026-10-21): 방 확장 결제 원장. 돈은 원장 밖(Vault)이라 여기서는 단계와 방 크기만 본다. */
class ExpandLedgerTest {
    private Connection c;
    private final ExpandLedger ledger = new ExpandLedger("housing_expand_tx", "housing_room");
    private final UUID owner = UUID.randomUUID();

    @BeforeEach void open() throws Exception {
        c = DriverManager.getConnection("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        try (var s = c.createStatement()) { s.execute("CREATE TABLE housing_room(owner CHAR(36) NOT NULL PRIMARY KEY, size INT NOT NULL, rev BIGINT NOT NULL)"); }
        ledger.create(c, "");
        try (var s = c.prepareStatement("INSERT INTO housing_room(owner,size,rev) VALUES(?,?,0)")) { s.setString(1, owner.toString()); s.setInt(2, 3); s.executeUpdate(); }
    }
    @AfterEach void close() throws Exception { c.close(); }

    private int size() throws Exception {
        try (var s = c.prepareStatement("SELECT size FROM housing_room WHERE owner=?")) { s.setString(1, owner.toString()); try (var r = s.executeQuery()) { r.next(); return r.getInt(1); } }
    }
    private String state(String id) throws Exception { return ledger.get(c, id, false).state(); }

    @Test void appliedWhenSizeMatches() throws Exception {
        ledger.prepare(c, "a", owner, 3, 4, 100, "school");
        assertTrue(ledger.move(c, "a", ExpandLedger.PREPARED, ExpandLedger.WITHDRAWN, "출금됨"));
        assertEquals(ExpandLedger.APPLIED, ledger.apply(c, "a", true));
        assertEquals(4, size());
        // 같은 거래를 다시 반영해도 바뀌지 않는다 (멱등)
        assertEquals(ExpandLedger.APPLIED, ledger.apply(c, "a", true));
        assertEquals(4, size());
    }

    @Test void refundPendingOnceWhenSizeChanged() throws Exception {
        ledger.prepare(c, "b", owner, 4, 5, 300, "school");     // 방은 3인데 4→5 를 요청 (다른 창에서 이미 바뀜)
        ledger.move(c, "b", ExpandLedger.PREPARED, ExpandLedger.WITHDRAWN, "출금됨");
        assertEquals(ExpandLedger.REFUND_PENDING, ledger.apply(c, "b", true));
        assertEquals(3, size());
        assertEquals(ExpandLedger.REFUND_PENDING, ledger.apply(c, "b", true));
        assertTrue(ledger.move(c, "b", ExpandLedger.REFUND_PENDING, ExpandLedger.DONE, "환불함"));
        assertFalse(ledger.move(c, "b", ExpandLedger.REFUND_PENDING, ExpandLedger.DONE, "환불함"));   // 두 번 환불 처리 안 됨
    }

    @Test void restartMarksUnfinishedUncertainWithoutTouchingMoney() throws Exception {
        ledger.prepare(c, "p", owner, 3, 4, 100, "school");
        ledger.prepare(c, "w", owner, 3, 4, 100, "school"); ledger.move(c, "w", ExpandLedger.PREPARED, ExpandLedger.WITHDRAWN, "출금됨");
        ledger.prepare(c, "r", owner, 3, 4, 100, "school"); ledger.move(c, "r", ExpandLedger.PREPARED, ExpandLedger.REFUND_PENDING, "x");
        ledger.prepare(c, "ok", owner, 3, 4, 100, "school"); ledger.move(c, "ok", ExpandLedger.PREPARED, ExpandLedger.APPLIED, "넓힘");
        ledger.prepare(c, "other", owner, 3, 4, 100, "wild");   // 다른 서버 거래는 건드리지 않는다
        assertArrayEquals(new int[]{3, 1}, ledger.recover(c, "school"));
        assertEquals(ExpandLedger.UNCERTAIN, state("p"));
        assertEquals(ExpandLedger.UNCERTAIN, state("w"));
        assertEquals(ExpandLedger.UNCERTAIN, state("r"));
        assertEquals(ExpandLedger.DONE, state("ok"));
        assertEquals(ExpandLedger.PREPARED, state("other"));
        assertEquals(3, size());
        // uncertain 은 반영되지 않는다 (자동으로 넓히거나 돈을 움직이지 않음)
        assertEquals(ExpandLedger.UNCERTAIN, ledger.apply(c, "w", true));
        assertEquals(3, size());
    }

    @Test void messagesNeverClaimARefundThatDidNotHappen() {
        assertTrue(ExpandLedger.message("refunded", 4, 100).contains("돌려드렸어요"));
        assertFalse(ExpandLedger.message(ExpandLedger.UNCERTAIN, 4, 100).contains("돌려드렸어요"));
        assertFalse(ExpandLedger.message(ExpandLedger.REFUND_PENDING, 4, 100).contains("돌려드렸어요"));
        assertTrue(ExpandLedger.message(ExpandLedger.CANCELLED, 4, 1000).contains("1,000"));
        assertTrue(ExpandLedger.message(ExpandLedger.APPLIED, 4, 100).contains("4×4"));
    }
}
