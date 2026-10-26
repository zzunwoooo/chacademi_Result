package school.magiccodex.client;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import school.magiccodex.protocol.MileageProtocol;
import school.magiccodex.protocol.MileageProtocol.*;

/** Round trip of the 마일리지 상점 frames, requests and the total/amount rules. */
class MileageProtocolTest {
    @Test void stateRoundTrip() {
        var products = List.of(new Product("p1", 0, "귀환권", "지정된 귀환 지점으로 이동합니다", 300, true, new byte[]{1, 2, 3}),
                new Product("p2", 2, "낚시용품 뽑기권", "", 500, false, new byte[0]));
        var state = new State(MileageProtocol.KIND_OPEN, true, 12500, products, "p2", "");
        var back = (State) MileageProtocol.decode(MileageProtocol.encode(state));
        assertEquals(state.kind(), back.kind());
        assertEquals(state.admin(), back.admin());
        assertEquals(state.balance(), back.balance());
        assertEquals(state.select(), back.select());
        assertEquals(2, back.products().size());
        for (int i = 0; i < 2; i++) {
            var a = products.get(i); var b = back.products().get(i);
            assertEquals(a.id(), b.id()); assertEquals(a.category(), b.category()); assertEquals(a.name(), b.name());
            assertEquals(a.description(), b.description()); assertEquals(a.price(), b.price()); assertEquals(a.enabled(), b.enabled());
            assertArrayEquals(a.preview(), b.preview());
        }
        var result = new Result(42, true, 12200, "귀환권 ×1 구매 완료");
        assertEquals(result, MileageProtocol.decode(MileageProtocol.encode(result)));
    }

    @Test void requestRoundTrip() {
        var edit = new Edit("p1", 1, "이벤트 상자", "설명", 1000, true);
        for (var r : List.of(Request.of(MileageProtocol.OPEN), Request.of(MileageProtocol.CLOSE), Request.of(MileageProtocol.ADMIN_OPEN),
                Request.buy("p1", 3, 300, 99), Request.add(edit, 0), Request.add(edit, 35), Request.save(edit),
                Request.replace("p1", 12), Request.delete("p1"), Request.move("p1", true), Request.move("p1", false)))
            assertEquals(r, MileageProtocol.request(MileageProtocol.request(r)));
    }

    @Test void rejectsBadInput() {
        assertThrows(IllegalArgumentException.class, () -> Request.buy("p1", 0, 300, 1));
        assertThrows(IllegalArgumentException.class, () -> Request.buy("p1", MileageProtocol.MAX_QUANTITY + 1, 300, 1));
        assertThrows(IllegalArgumentException.class, () -> new Edit("", 3, "x", "", 1, true));
        assertThrows(IllegalArgumentException.class, () -> new Edit("", 0, " ", "", 1, true));
        assertThrows(IllegalArgumentException.class, () -> new Edit("", 0, "x", "", -1, true));
        assertThrows(IllegalArgumentException.class, () -> MileageProtocol.request(new byte[]{0, 0, 0, 9, 0}));
        assertThrows(IllegalArgumentException.class, () -> Request.add(new Edit("", 0, "x", "", 1, true), 36));
        assertThrows(IllegalArgumentException.class, () -> Request.replace("p1", -1));
        byte[] ok = MileageProtocol.request(Request.of(MileageProtocol.OPEN));
        byte[] trailing = java.util.Arrays.copyOf(ok, ok.length + 1);
        assertThrows(IllegalArgumentException.class, () -> MileageProtocol.request(trailing));
    }

    @Test void totals() {
        assertEquals(900, MileageProtocol.total(300, 3));
        assertEquals(-1, MileageProtocol.total(MileageProtocol.MAX_PRICE, 99_999));
        assertEquals("12,500", MileageProtocol.amount(12500));
    }
}
