package school.magiccodex.paper;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import school.magiccodex.protocol.ArtifactFxProtocol;

class ArtifactFxProtocolTest {
    @Test void messagesRoundTrip() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        var states = new ArtifactFxProtocol.States(true, Map.of(a, ArtifactFxProtocol.SPIRIT | ArtifactFxProtocol.PARROT, b, 0));
        assertEquals(states, ArtifactFxProtocol.decode(ArtifactFxProtocol.encode(states)));
        var shot = new ArtifactFxProtocol.Shot(ArtifactFxProtocol.TWIN_ECHO, a, 1234);
        assertEquals(shot, ArtifactFxProtocol.decode(ArtifactFxProtocol.encode(shot)));
        var bubble = new ArtifactFxProtocol.Bubble(b, ArtifactFxProtocol.bubbleText("안녕하세요 반가워요"));
        assertEquals(bubble, ArtifactFxProtocol.decode(ArtifactFxProtocol.encode(bubble)));
    }
    @Test void bubbleTextIsTrimmedAndClean() {
        assertEquals("", ArtifactFxProtocol.bubbleText("  §c \n "));
        assertEquals("a b", ArtifactFxProtocol.bubbleText(" a \n\t b "));
        String cut = ArtifactFxProtocol.bubbleText("가".repeat(30));
        assertEquals(ArtifactFxProtocol.MAX_BUBBLE + 1, cut.codePointCount(0, cut.length()));
        assertTrue(cut.endsWith("…"));
    }
    @Test void badInputRejected() {
        byte[] good = ArtifactFxProtocol.encode(new ArtifactFxProtocol.Shot(ArtifactFxProtocol.SPIRIT_SHOT, UUID.randomUUID(), 1));
        assertThrows(IllegalArgumentException.class, () -> ArtifactFxProtocol.decode(Arrays.copyOf(good, good.length + 1)));
        assertThrows(IllegalArgumentException.class, () -> new ArtifactFxProtocol.States(false, Map.of(UUID.randomUUID(), 99)));
        assertThrows(IllegalArgumentException.class, () -> ArtifactFxProtocol.decode(new byte[1]));
    }
}
