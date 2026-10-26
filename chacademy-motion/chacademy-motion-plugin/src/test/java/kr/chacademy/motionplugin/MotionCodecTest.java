package kr.chacademy.motionplugin;

import org.junit.jupiter.api.Test;

import java.util.HexFormat;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 모드 쪽 코덱(ByteBufCodecs.STRING_UTF8, UUIDUtil.STREAM_CODEC)이 만드는 바이트와 똑같은지 확인.
 */
class MotionCodecTest {

    private static final HexFormat HEX = HexFormat.of();

    @Test
    void castPacketMatchesMinecraftStringFormat() throws Exception {
        // "wand_cast" = 9바이트 → VarInt 0x09 + ASCII
        byte[] data = HEX.parseHex("09" + "77616e645f63617374");
        assertEquals("wand_cast", MotionCodec.decodeCast(data));
        assertArrayEquals(data, MotionCodec.encodeCast("wand_cast"));
    }

    @Test
    void koreanNameUsesUtf8ByteLength() throws Exception {
        // "불꽃" = UTF-8 6바이트
        byte[] data = MotionCodec.encodeCast("불꽃");
        assertEquals(7, data.length);
        assertEquals(6, data[0]);
        assertEquals("불꽃", MotionCodec.decodeCast(data));
    }

    @Test
    void animPacketIsUuidThenString() throws Exception {
        UUID id = UUID.fromString("00112233-4455-6677-8899-aabbccddeeff");
        byte[] data = MotionCodec.encodeAnim(id, "wand_cast");
        assertEquals("00112233445566778899aabbccddeeff" + "09" + "77616e645f63617374", HEX.formatHex(data));
        Object[] back = MotionCodec.decodeAnim(data);
        assertEquals(id, back[0]);
        assertEquals("wand_cast", back[1]);
    }

    @Test
    void multiByteVarInt() throws Exception {
        String s = "a".repeat(200); // 200 = 0xC8 0x01
        byte[] data = MotionCodec.encodeCast(s);
        assertEquals((byte) 0xC8, data[0]);
        assertEquals((byte) 0x01, data[1]);
        // 64바이트 제한을 넘으므로 decode 는 거부해야 함
        assertThrows(MotionCodec.MalformedPacketException.class, () -> MotionCodec.decodeCast(data));
    }

    @Test
    void rejectsBrokenPackets() {
        assertThrows(MotionCodec.MalformedPacketException.class, () -> MotionCodec.decodeCast(new byte[0]));
        assertThrows(MotionCodec.MalformedPacketException.class, () -> MotionCodec.decodeCast(HEX.parseHex("0577")));
        assertThrows(MotionCodec.MalformedPacketException.class, () -> MotionCodec.decodeCast(HEX.parseHex("0161ff")));
        assertThrows(MotionCodec.MalformedPacketException.class, () -> MotionCodec.decodeCast(HEX.parseHex("ffffffffff0f")));
    }
}
