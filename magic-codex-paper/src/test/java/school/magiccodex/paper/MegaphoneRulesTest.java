package school.magiccodex.paper;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.util.List;
import org.junit.jupiter.api.Test;
import school.magiccodex.protocol.MegaphoneProtocol;

/** 확성기 (메모 #102) 순수 규칙: 설정 정리·금칙어·서버 간 메시지. */
class MegaphoneRulesTest {
    @Test void settingsAreClamped() {
        var s = MegaphoneRules.settings(" ITEM:Megaphone ", -5, 9999, 0.5, false, 1, true, List.of(" 시 발 ", "", "시발"));
        assertEquals("item:megaphone", s.item());
        assertEquals(0, s.cooldownMs());
        assertEquals(MegaphoneRules.MAX_LENGTH, s.maxLength());
        assertTrue(MegaphoneRules.MAX_LENGTH * 2 <= MegaphoneProtocol.MAX_TEXT);   // 두 칸 글자만 써도 프로토콜 안
        assertEquals(MegaphoneProtocol.MIN_DURATION_MS, s.showMs());
        assertEquals(10_000, s.promptTimeoutMs());
        assertEquals(List.of("시발"), s.bannedWords());
        var d = MegaphoneRules.settings("item:megaphone", 30, 80, 6, true, 120, true, null);
        assertEquals(30_000, d.cooldownMs());
        assertEquals(80, d.maxLength());
        assertEquals(6_000, d.showMs());
        assertFalse(d.bannedWords().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> MegaphoneRules.settings("megaphone", 30, 80, 6, true, 120, true, null));
    }

    @Test void bannedIgnoresSpacesAndPunctuation() {
        var words = List.of(MegaphoneRules.normalize("시발"));
        assertTrue(MegaphoneRules.banned("아 시.발 진짜", words));
        assertTrue(MegaphoneRules.banned("시 발", words));
        assertFalse(MegaphoneRules.banned("시원한 발", List.of(MegaphoneRules.normalize("시발 놈"))));
        assertFalse(MegaphoneRules.banned("광장에서 만나요", words));
        assertFalse(MegaphoneRules.banned("", words));
    }

    @Test void cleanCutsAndStripsColors() {
        assertEquals("안녕 하세요", MegaphoneRules.clean("  §c안녕   \n하세요 ", 80));
        assertEquals("abc", MegaphoneRules.clean("abcdef", 3));
        // code point 로 센다: 이모지 80개 = 80자, 반으로 자르지 않음
        String e = MegaphoneRules.clean("😀".repeat(90), 80);
        assertEquals(80, e.codePointCount(0, e.length()));
        assertEquals(80, MegaphoneRules.clean("가".repeat(90), 80).length());
    }

    @Test void senderNameFitsProtocol() {
        assertEquals("준우", MegaphoneRules.senderName(" 준우 "));
        assertEquals("?", MegaphoneRules.senderName(" "));
        String longName = MegaphoneRules.senderName("😀".repeat(40));
        assertTrue(longName.length() <= MegaphoneProtocol.MAX_SENDER);
        assertFalse(Character.isHighSurrogate(longName.charAt(longName.length() - 1)));
    }

    @Test void wireRoundTripThroughForward() throws Exception {
        byte[] item = new byte[1000]; item[0] = 7;
        var w = new MegaphoneRules.Wire("origin", "id-1", "school", "차카냥", "광장에서 만나요", 6000, item);
        byte[] forward = MegaphoneRules.forwardAll(MegaphoneRules.encode(w));
        var in = new DataInputStream(new ByteArrayInputStream(forward));
        assertEquals("Forward", in.readUTF());
        assertEquals("ALL", in.readUTF());
        byte[] rest = in.readAllBytes();   // 받는 쪽은 하위 채널부터 받는다
        var back = MegaphoneRules.decode(MegaphoneRules.unwrapForward(rest));
        assertNotNull(back);
        assertEquals("id-1", back.id());
        assertEquals("차카냥", back.sender());
        assertEquals("광장에서 만나요", back.text());
        assertEquals(6000, back.durationMs());
        assertArrayEquals(item, back.item());
    }

    @Test void largestWireFitsServerboundLimit() {
        var w = new MegaphoneRules.Wire("o".repeat(36), "i".repeat(36), "school", "가".repeat(MegaphoneProtocol.MAX_SENDER),
                "나".repeat(MegaphoneProtocol.MAX_TEXT), 30000, new byte[MegaphoneProtocol.MAX_ITEM_BYTES]);
        assertTrue(MegaphoneRules.forwardAll(MegaphoneRules.encode(w)).length < 32767);
    }

    @Test void brokenOrForeignMessagesAreIgnored() {
        assertNull(MegaphoneRules.decode(null));
        assertNull(MegaphoneRules.decode(new byte[]{9, 9, 9}));
        assertNull(MegaphoneRules.unwrapForward(new byte[]{0, 1, 'x', 0, 0}));
        byte[] good = MegaphoneRules.encode(new MegaphoneRules.Wire("o", "i", "", "a", "b", 6000, null));
        byte[] trailing = java.util.Arrays.copyOf(good, good.length + 1);
        assertNull(MegaphoneRules.decode(trailing));
        // 표시 시간은 범위 안으로
        assertEquals(MegaphoneProtocol.MAX_DURATION_MS, MegaphoneRules.decode(MegaphoneRules.encode(new MegaphoneRules.Wire("o", "i", "", "a", "b", 999_999, null))).durationMs());
    }
}
