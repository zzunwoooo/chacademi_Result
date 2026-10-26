package school.chacademia.housing;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import school.magiccodex.protocol.HousingProtocol;
import school.magiccodex.protocol.HousingProtocol.*;
import school.magiccodex.protocol.HousingRules.Layout;
import school.magiccodex.protocol.HousingRules.Placement;

/** Same GOLDEN strings as magic-codex-fabric HousingProtocolTest: the plugin and the client mod must agree byte for byte. */
class HousingWireTest {
    static final UUID A = new UUID(1, 2);
    static final Layout LAYOUT = new Layout("wall_star", "", List.of(new Placement("bed", 1, 0, 0, 1), new Placement("frame", 2, 1, 0, 0)));
    static final Room ROOM = new Room(HousingProtocol.F_OPEN | HousingProtocol.F_EDIT, new Person(A, "준우", 1, "", false), 3, 4, 5000, 12, 2, 40, 0,
            Map.of(0, LAYOUT), Map.of("bed", 1, "frame", 2, "wall_star", 1));
    static final String GOLDEN_ROOM = "010103000000000000000100000000000000020006eca480ec9ab001000000030400000000000013880000000c0000000200000028000100000977616c6c5f7374617200000200036265640100000100056672616d650201000000030003626564000100056672616d650002000977616c6c5f737461720001";
    static final String GOLDEN_SAVE = "010302000977616c6c5f7374617200000200036265640100000100056672616d6502010000";

    @Test void golden() {
        var hex = HexFormat.of();
        assertEquals(GOLDEN_ROOM, hex.formatHex(HousingProtocol.encode(new RoomMessage(ROOM))));
        assertEquals(new Save(2, LAYOUT), HousingProtocol.decodeRequest(hex.parseHex(GOLDEN_SAVE)));
    }
}
