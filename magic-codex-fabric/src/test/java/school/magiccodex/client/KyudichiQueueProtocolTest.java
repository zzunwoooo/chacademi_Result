package school.magiccodex.client;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import school.magiccodex.protocol.KyudichiQueueProtocol;
import school.magiccodex.protocol.KyudichiQueueProtocol.*;

/** Locks the 큐디치 queue/dashboard (client ↔ proxy) and link (proxy ↔ arena) wire formats. */
class KyudichiQueueProtocolTest {
    static final UUID A = new UUID(1, 2), B = new UUID(3, 4), M = new UUID(9, 9);
    static final Live LIVE = new Live("경기장1", 2, 20, 10, 300000, 3, "준우 · 서하 · 도윤", "하린 · 지우 · 시우", 0, 2, -1);

    @Test void toClientRoundTrip() {
        var rank = new Rank(1, A, "준우", "0123456789abcdef0123456789abcdef", 1712, 6, 30, 22, 8);
        var rec = new PlayerRecord(A, "준우", "", 1712, 6, 1, 30, 22, 8, 0, 41, 77, 30, 12, 3, 19, 9, 51234.5f, 8000f,
                List.of(new Recent(1_760_000_000_000L, 1, 0, 80, 50, 3, 2, 0, true, 18), new Recent(1_759_000_000_000L, 2, 1, 40, 40, 0, 1, 1, false, 0)));
        for (ToClient m : List.of(new Status(1, 125, 0, 0, 0, false, 7, 0), new Status(2, 0, 9, 4, 6, true, 3, 1),
                new Dash(5, List.of(new LiveCard("경기장1", 2, 20, 10, 300000, 3, List.of(new Who(A, "준우", ""), new Who(B, "서하", "ab")), List.of()))),
                new Apply(1250, 18, 12, 1250, true, "파티장만 시작할 수 있습니다.", List.of(new Member(A, "준우", "", 0, 1250, true), new Member(B, "루나", "", -1, 1280, false))), new Ranking(List.of(rank), rank), new Ranking(List.of(), null), new RecordReply(rec), new RecordReply(null),
                new Lobby(true, KyudichiQueueProtocol.TAB_MATCH, 1, List.of(new ModeInfo(3, 86400 * 5, 1250, 2, 1100, 1300, 32, 18, 2, 14, true, ""), new ModeInfo(1, 600, 1000, 1, 900, 1100, 0, 0, 0, 6, false, "파티를 나가야 해요")),
                        List.of(new Member(A, "준우", "", 0, 1250, true))),
                new Board(1, List.of(new Card("결투장 1", 2, 1, 0, 136000, 2, -1, -1, "준우", "서하", List.of(new Fighter(A, "준우", "", 1250, 2)), List.of(new Fighter(B, "서하", "ab", 1780, 5))))),
                new Ranks(0, 2, 9, List.of(new Row(7, A, "준우", "", 1624, 4, 20, 10, 1)), new Row(47, B, "서하", "", 1250, 2, 32, 18, 2), 7, ""),
                new ProfileReply(1, "", new Profile(A, "준우", "", 1250, 2, 47, 52, 32, 18, 2, 0, 86, 54, 0, 0, 64480f, 0f,
                        List.of(new Game(1_760_000_000_000L, 1, 14, 1264, 0, 8, 3, 2410f, 2, 1, "서리월", "", -1)))),
                new ProfileReply(0, "없는사람", null)))
            assertEquals(m, KyudichiQueueProtocol.decodeToClient(KyudichiQueueProtocol.encode(m)));
    }

    @Test void fromClientRoundTrip() {
        for (FromClient m : List.of(new Join(), new Leave(), new Accept(), new Decline(), new DashRequest(), new RankingRequest(),
                new RecordRequest(""), new RecordRequest("서하"), new SpectateRequest("경기장1"), new ClientHello(), new PracticeRequest(), new ApplyRequest(),
                new JoinMode(1), new LobbyRequest(2, 0), new BoardRequest(1), new RanksRequest(0, 3, "서하"), new ProfileRequest(1, ""), new PracticeMode(1)))
            assertEquals(m, KyudichiQueueProtocol.decodeFromClient(KyudichiQueueProtocol.encode(m)));
    }

    @Test void linkRoundTrip() {
        for (ToArena m : List.of(new Assign(M, true, List.of(A), List.of(B), 1), new KyudichiQueueProtocol.Spectate(A, ""), new Practice(B, 1)))
            assertEquals(m, KyudichiQueueProtocol.decodeToArena(KyudichiQueueProtocol.encode(m)));
        for (ToProxy m : List.of(new Hello(A), new LiveList(2, List.of(new LiveMatch(M, LIVE, List.of(A), List.of(B))), 1), new Return(List.of(A, B)), new Ended(M),
                new Custom(M, 1, List.of(A), List.of(B)), new Custom(M, 0, List.of(A, B), List.of(M))))
            assertEquals(m, KyudichiQueueProtocol.decodeToProxy(KyudichiQueueProtocol.encode(m)));
    }

    // 2026-10-18: ASSIGN and STATUS gained a trailing u8 mode (client, proxy and arena are updated together)
    static final String GOLDEN_ASSIGN = "010100000000000000090000000000000009010100000000000000010000000000000002010000000000000003000000000000000400";
    static final String GOLDEN_STATUS = "010102000007030601000401";
    static final String GOLDEN_HELLO = "010100000000000000010000000000000002";

    @Test void golden() {
        var hex = HexFormat.of();
        assertEquals(GOLDEN_ASSIGN, hex.formatHex(KyudichiQueueProtocol.encode(new Assign(M, true, List.of(A), List.of(B), 0))));
        assertEquals(new Status(2, 0, 7, 3, 6, true, 4, 1), KyudichiQueueProtocol.decodeToClient(hex.parseHex(GOLDEN_STATUS)));
        assertEquals(new Hello(A), KyudichiQueueProtocol.decodeToProxy(hex.parseHex(GOLDEN_HELLO)));
    }

    @Test void strict() {
        var hex = HexFormat.of();
        assertThrows(IllegalArgumentException.class, () -> KyudichiQueueProtocol.decodeToClient(hex.parseHex(GOLDEN_STATUS + "00")));
        assertThrows(IllegalArgumentException.class, () -> KyudichiQueueProtocol.decodeFromClient(hex.parseHex("0163")));
        assertThrows(IllegalArgumentException.class, () -> KyudichiQueueProtocol.decodeToArena(hex.parseHex("0201")));
        assertThrows(IllegalArgumentException.class, () -> KyudichiQueueProtocol.decodeToProxy(hex.parseHex("01")));
        // 커스텀 경기: 빈 팀은 보내지도 받지도 않는다
        assertThrows(IllegalArgumentException.class, () -> KyudichiQueueProtocol.encode(new Custom(M, 0, List.of(), List.of(B))));
    }
}
