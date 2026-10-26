package school.chacademia.magicmythic;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class TelegraphMechTest {
    @Test void vfxIdGetsThePackPrefix() {
        assertEquals("claude:sealcircle/seal", TelegraphMech.vfxId("sealcircle/seal"));
        assertEquals("claude:sealcircle/seal", TelegraphMech.vfxId("Claude:SealCircle/seal"));
        assertEquals("", TelegraphMech.vfxId(""));
        assertEquals("", TelegraphMech.vfxId("bad id!"));
    }

    @Test void tintParsesHex() {
        assertEquals(0xFFFFFF, TelegraphMech.tint("FFFFFF"));
        assertEquals(0x7FC8FF, TelegraphMech.tint("#7FC8FF"));
        assertEquals(0xFFFFFF, TelegraphMech.tint("nope"));
    }
}
