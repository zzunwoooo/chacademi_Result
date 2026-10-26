package school.magiccodex.paper;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import org.junit.jupiter.api.Test;

class ConvenienceRulesTest {
    @Test void cleanRemovesColorCodesControlAndExtraSpaces() {
        assertEquals("안녕 하세요", ConvenienceRules.clean("  §c안녕   \n하세요\t ", 100));
        assertEquals("", ConvenienceRules.clean("   ", 100));
        assertEquals("", ConvenienceRules.clean(null, 100));
        assertEquals("abc", ConvenienceRules.clean("abcdef", 3));
    }

    @Test void cooldownCountsDownAndMessageRoundsUp() {
        assertEquals(0, ConvenienceRules.cooldownLeft(-1, 1000, 60_000));
        assertEquals(59_000, ConvenienceRules.cooldownLeft(0, 1000, 60_000));
        assertEquals(0, ConvenienceRules.cooldownLeft(0, 60_000, 60_000));
        assertEquals(0, ConvenienceRules.cooldownLeft(0, 1, 0));
        assertEquals("대기 2초", ConvenienceRules.cooldownMessage("대기 {초}초", 1_200));
        assertEquals("대기 1초", ConvenienceRules.cooldownMessage("대기 {초}초", 0));
    }

    @Test void settingsAreClamped() {
        var s = ConvenienceRules.settings("", null, true, -5, 9999, null, 5, 1, null, " ", null, null, 10);
        assertEquals("운영자 공지", s.noticeTitle());
        assertEquals(0, s.helpCooldownMs());
        assertEquals(500, s.helpMaxLength());
        assertEquals(20, s.helpAckWaitTicks());
        assertEquals(10, s.helpKeep());
        assertEquals(6, s.trashRows());
        assertFalse(s.noticeSound().enabled());
        assertEquals(ConvenienceRules.Settings.DEFAULT.helpNoOperator(), s.helpNoOperator());
    }

    @Test void soundKeyIsValidated() {
        assertTrue(ConvenienceRules.sound("minecraft:block.note_block.bell", 1, 1).enabled());
        assertFalse(ConvenienceRules.sound("없음", 1, 1).enabled());
        assertFalse(ConvenienceRules.sound("나쁜 이름", 1, 1).enabled());
        assertEquals(2f, ConvenienceRules.sound("a.b", 1, 9).pitch());
    }

    @Test void wireRoundTripsThroughForward() throws Exception {
        var w = new ConvenienceRules.Wire(ConvenienceRules.Kind.HELP, "o1", "id1", "wild", "별빛", "Star_1", "길을 잃었어요", 123L);
        byte[] forward = ConvenienceRules.forwardAll(ConvenienceRules.encode(w));
        var in = new DataInputStream(new ByteArrayInputStream(forward));
        assertEquals("Forward", in.readUTF());
        assertEquals("ALL", in.readUTF());
        // 받는 쪽은 "Forward"·"ALL" 을 뺀 나머지(하위 채널 + 길이 + 내용)를 받는다
        byte[] received = in.readAllBytes();
        assertEquals(w, ConvenienceRules.decode(ConvenienceRules.unwrapForward(received)));
    }

    @Test void brokenOrForeignMessagesAreIgnored() {
        assertNull(ConvenienceRules.decode(null));
        assertNull(ConvenienceRules.decode(new byte[]{9, 0, 0}));
        assertNull(ConvenienceRules.unwrapForward(new byte[]{0, 3, 'a', 'b', 'c', 0, 1, 1}));
    }

    @Test void numbersForMemoryCommand() {
        assertEquals("2MB", ConvenienceRules.megabytes(2L * 1024 * 1024 + 5));
        assertEquals("20.0", ConvenienceRules.tps(20.4));
        assertEquals("?", ConvenienceRules.tps(Double.NaN));
    }
}
