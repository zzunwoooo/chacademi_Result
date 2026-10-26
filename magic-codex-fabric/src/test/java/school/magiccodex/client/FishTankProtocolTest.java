package school.magiccodex.client;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import school.magiccodex.protocol.FishTankProtocol;
import school.magiccodex.protocol.FishTankProtocol.*;

/** Round trip of the 아공간 수조 frames; a full tank (100 fish + full inventory) fits one packet. */
class FishTankProtocolTest {
    static final String ID = "3f2a9c1e-7b44-4c1d-9a0e-5d2c8b1f6a77";

    @Test void roundTrip() {
        var state = new State(FishTankProtocol.KIND_OPEN, 100, true,
                List.of(new Fish(ID, "fish_121", 42.8f, 1_792_000_000_000L, 120, false), new Fish("0b1c2d3e-0000-4000-8000-000000000001", "fish_001", 21.4f, 1L, 0, true)),
                List.of(new Held(3, ID, "fish_121", 31.6f, 1_792_000_000_000L), new Held(40, "", "fish_001", 18.2f, 0)),
                List.of(new Info("fish_121", "백은 용잉어", 2, 0, "용의 수염과 은빛 비늘을 지닌 잉어."), new Info("fish_001", "붕어", 0, 0, "")), "");
        assertEquals(state, FishTankProtocol.decode(FishTankProtocol.encode(state)));
        var result = new Result(42, FishTankProtocol.SELL, true, ID, "120 G에 팔았어요.");
        assertEquals(result, FishTankProtocol.decode(FishTankProtocol.encode(result)));
        for (var r : List.of(Request.of(FishTankProtocol.QUERY), Request.fish(FishTankProtocol.ITEMIZE, ID, 7), Request.fish(FishTankProtocol.RELEASE, ID, 8),
                Request.sell(ID, 120, 9), Request.insert(40, "", 10), Request.insert(35, ID, 11), Request.of(FishTankProtocol.CLOSE)))
            assertEquals(r, FishTankProtocol.request(FishTankProtocol.request(r)));
    }

    @Test void fullTankFits() {
        var fish = new ArrayList<Fish>(); var held = new ArrayList<Held>(); var info = new ArrayList<Info>();
        String desc = "가".repeat(FishTankProtocol.MAX_DESCRIPTION);
        for (int i = 0; i < 100; i++) { fish.add(new Fish(ID, String.format("fish_%03d", i + 1), 99.9f, 1L, 99999, false)); info.add(new Info(String.format("fish_%03d", i + 1), "가".repeat(20), 3, 4, desc)); }
        for (int i = 0; i < 36; i++) { held.add(new Held(i, ID, String.format("fish_%03d", 101 + i), 1, 1)); info.add(new Info(String.format("fish_%03d", 101 + i), "가".repeat(20), 3, 4, desc)); }
        var state = new State(FishTankProtocol.KIND_STATE, 100, false, fish, held, info, "");
        assertEquals(state, FishTankProtocol.decode(FishTankProtocol.encode(state)));
    }

    @Test void rejectsBadInput() {
        assertThrows(IllegalArgumentException.class, () -> Request.insert(36 + 5, ID, 1));
        assertThrows(IllegalArgumentException.class, () -> Request.fish(FishTankProtocol.ITEMIZE, "../x", 1));
        assertThrows(IllegalArgumentException.class, () -> FishTankProtocol.request(new byte[]{0, 0, 0, 9, 0}));
    }
}
