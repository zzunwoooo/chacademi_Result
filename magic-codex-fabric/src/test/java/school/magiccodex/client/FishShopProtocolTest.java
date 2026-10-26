package school.magiccodex.client;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import school.magiccodex.protocol.FishShopProtocol;
import school.magiccodex.protocol.FishShopProtocol.*;

/** Round trip of the 물고기 판매소 frames and the shared price rule. */
class FishShopProtocolTest {
    @Test void roundTrip() {
        var grades = List.of(new Grade(32, 3200, "fish_023"), new Grade(12, 6000, "fish_121"), new Grade(3, 9000, ""), new Grade(1, 12000, "fish_181"));
        var state = new State(FishShopProtocol.KIND_OPEN, "물고기 판매소", "물고기 매입상", "에드윈", "edwin", "988100", 48, 100, grades,
                List.of("fish_021", "fish_022"), List.of(new Species("fish_001", "붕어", 0)), "");
        assertEquals(state, FishShopProtocol.decode(FishShopProtocol.encode(state)));
        var result = new Result(42, true, 48, 30200, "48마리를 팔았어요.");
        assertEquals(result, FishShopProtocol.decode(FishShopProtocol.encode(result)));
        for (var r : List.of(Request.of(FishShopProtocol.QUERY), Request.sell(0b1011, 30200, 42), Request.locks(List.of("fish_021", "fish_200")), Request.of(FishShopProtocol.CLOSE)))
            assertEquals(r, FishShopProtocol.request(FishShopProtocol.request(r)));
    }

    @Test void price() {
        assertEquals(56, FishShopProtocol.price(80, 10, 10, 30, .7, 1.5));     // 붕어 smallest
        assertEquals(120, FishShopProtocol.price(80, 30, 10, 30, .7, 1.5));    // 붕어 largest
        assertEquals(88, FishShopProtocol.price(80, 20, 10, 30, .7, 1.5));     // middle
        assertEquals(15000, FishShopProtocol.price(10000, 200, 150, 200, .7, 1.5));
    }
}
