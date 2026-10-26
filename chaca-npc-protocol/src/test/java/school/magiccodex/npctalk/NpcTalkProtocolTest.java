package school.magiccodex.npctalk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NpcTalkProtocolTest {
    private final String session = UUID.randomUUID().toString();

    @Test void requestRoundTripAndValidation() {
        var say = new NpcTalkProtocol.Request(NpcTalkProtocol.C_SAY, session, 3, "안녕 엘라!", false);
        assertEquals(say, NpcTalkProtocol.request(NpcTalkProtocol.encode(say)));
        var hello = new NpcTalkProtocol.Request(NpcTalkProtocol.C_HELLO, "", NpcTalkProtocol.VERSION, "magic-codex-ui", false);
        assertEquals(hello, NpcTalkProtocol.request(NpcTalkProtocol.encode(hello)));
        var quest = new NpcTalkProtocol.Request(NpcTalkProtocol.C_QUEST, session, 4, "abc123", true);
        assertEquals(quest, NpcTalkProtocol.request(NpcTalkProtocol.encode(quest)));
        byte[] packet = NpcTalkProtocol.encode(say);
        assertThrows(IllegalArgumentException.class, () -> NpcTalkProtocol.request(Arrays.copyOf(packet, packet.length - 1)));
        assertThrows(IllegalArgumentException.class, () -> NpcTalkProtocol.request(Arrays.copyOf(packet, packet.length + 1)));
        // 세션 위조, 잘못된 버튼 id, 음수 순번
        assertThrows(IllegalArgumentException.class, () -> NpcTalkProtocol.request(NpcTalkProtocol.encode(
                new NpcTalkProtocol.Request(NpcTalkProtocol.C_SAY, "fake", 1, "hi", false))));
        assertThrows(IllegalArgumentException.class, () -> NpcTalkProtocol.request(NpcTalkProtocol.encode(
                new NpcTalkProtocol.Request(NpcTalkProtocol.C_BUTTON, session, 1, "rm -rf", false))));
        assertThrows(IllegalArgumentException.class, () -> NpcTalkProtocol.request(NpcTalkProtocol.encode(
                new NpcTalkProtocol.Request(NpcTalkProtocol.C_SAY, session, -1, "hi", false))));
    }

    @Test void inputLimitsCharsAndUtf8Bytes() {
        assertTrue(NpcTalkProtocol.validInput("가".repeat(100)));            // 100자, 300바이트
        assertFalse(NpcTalkProtocol.validInput("가".repeat(101)));
        assertFalse(NpcTalkProtocol.validInput("😀".repeat(101)));           // 코드포인트 기준
        assertTrue(NpcTalkProtocol.validInput("😀".repeat(100)));            // 400바이트
        assertFalse(NpcTalkProtocol.validInput("😀".repeat(100) + "a"));
        assertFalse(NpcTalkProtocol.validInput("   "));
        assertFalse(NpcTalkProtocol.validInput("줄\n바꿈"));
        assertFalse(NpcTalkProtocol.validInput("§c색코드"));
        assertThrows(IllegalArgumentException.class, () -> NpcTalkProtocol.request(NpcTalkProtocol.encode(
                new NpcTalkProtocol.Request(NpcTalkProtocol.C_SAY, session, 1, "가".repeat(101), false))));
    }

    @Test void responseRoundTripAndLimits() {
        var open = new NpcTalkProtocol.Response(NpcTalkProtocol.S_OPEN, session, 0, "엘라", "elena-neutral", "어머, 왔네?",
                List.of(new NpcTalkProtocol.Button("daily", "요즘 뭐 해?")), 2, true, "");
        assertEquals(open, NpcTalkProtocol.response(NpcTalkProtocol.encode(open)));
        var offer = new NpcTalkProtocol.Response(NpcTalkProtocol.S_QUEST_OFFER, session, 5, "", "", "잃어버린 책", List.of(), 0, false, "k3j2");
        assertEquals(offer, NpcTalkProtocol.response(NpcTalkProtocol.encode(offer)));
        var ack = NpcTalkProtocol.Response.simple(NpcTalkProtocol.S_HELLO_ACK, "", NpcTalkProtocol.VERSION, "ok");
        assertEquals(ack, NpcTalkProtocol.response(NpcTalkProtocol.encode(ack)));
        assertThrows(IllegalArgumentException.class, () -> NpcTalkProtocol.response(NpcTalkProtocol.encode(
                NpcTalkProtocol.Response.simple(NpcTalkProtocol.S_LINE, "nope", 1, "x"))));
        assertThrows(IllegalArgumentException.class, () -> NpcTalkProtocol.response(NpcTalkProtocol.encode(
                new NpcTalkProtocol.Response(NpcTalkProtocol.S_OPEN, session, 0, "엘라", "../evil", "x", List.of(), 0, true, ""))));
    }
}
