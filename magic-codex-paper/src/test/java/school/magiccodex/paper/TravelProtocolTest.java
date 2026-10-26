package school.magiccodex.paper;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import school.magiccodex.protocol.TravelLinkProtocol;
import school.magiccodex.protocol.TravelProtocol;
import school.magiccodex.protocol.TravelProtocol.*;

/** 지도 이동 패킷 (2026-10-23): round trips and the limits the server relies on. */
class TravelProtocolTest {
    static Tab tab(String id, int map) {
        return new Tab(id, "학교", "school", "world", map, map == TravelProtocol.MAP_IMAGE ? "magiccodex:textures/gui/travel/maps/school.png" : "",
                map == TravelProtocol.MAP_IMAGE ? 1024 : 0, map == TravelProtocol.MAP_IMAGE ? 512 : 0, -512, -256, 512, -256, -512, 256, "school/world/u", -1000, -1000, 1000, 1000, true, "");
    }

    @Test void stateRoundTrip() {
        var points = List.of(
                new Point("harbor", "아주 긴 이름의 항구 마을 동쪽 선착장 앞 광장", "school", TravelProtocol.POINT_PUBLIC, TravelProtocol.UNLOCKED, 1.5, 64, -2.5, ""),
                new Point("camp", "미발견 지점", "wild1", TravelProtocol.POINT_PUBLIC, TravelProtocol.LOCKED, 0, 0, 0, "아직 발견하지 못한 지점이에요"),
                new Point(TravelProtocol.PERSONAL_ID, "개인 지점", "", TravelProtocol.POINT_PERSONAL, TravelProtocol.UNAVAILABLE, 3, 4, 5, "지도에서 빠진 서버예요 (old)"));
        var s = new State(TravelProtocol.KIND_OPEN, new Position("school", 1, 2, 90), List.of(tab("school", TravelProtocol.MAP_IMAGE), tab("wild1", TravelProtocol.MAP_TERRAIN)), points, "");
        var back = (State) TravelProtocol.decode(TravelProtocol.encode(s));
        assertEquals(s, back);
        assertFalse(back.points().get(1).travelable());
    }

    @Test void requestsAndResults() {
        var r = TravelProtocol.Request.travel(TravelProtocol.PERSONAL_ID, 42);
        assertEquals(r, TravelProtocol.request(TravelProtocol.request(r)));
        assertThrows(IllegalArgumentException.class, () -> TravelProtocol.Request.travel("../x", 1));
        assertThrows(IllegalArgumentException.class, () -> TravelProtocol.Request.travel("harbor", 0));
        var res = new Result(9, TravelProtocol.STAGE_FAILED, "도착 서버가 닫혀 있어요");
        assertEquals(res, TravelProtocol.decode(TravelProtocol.encode(res)));
        var mk = new Markers("world", "wild/world/uid", List.of(new Marker("harbor", "항구", 1, 2, 3, 4, true)));
        assertEquals(mk, TravelProtocol.decode(TravelProtocol.encode(mk)));
    }

    @Test void rejectsBrokenFrames() {
        var bad = new byte[]{0, 0, 0, 1, 9};
        assertThrows(IllegalArgumentException.class, () -> TravelProtocol.decode(bad));
        byte[] ok = TravelProtocol.request(TravelProtocol.Request.of(TravelProtocol.QUERY));
        byte[] trailing = Arrays.copyOf(ok, ok.length + 1);
        assertThrows(IllegalArgumentException.class, () -> TravelProtocol.request(trailing));
        // a point on a tab the state does not have
        assertThrows(IllegalArgumentException.class, () -> new State(0, new Position("", 0, 0, 0), List.of(tab("school", 0)),
                List.of(new Point("a", "A", "wild9", 0, 0, 0, 0, 0, "")), ""));
    }

    @Test void openActionAndClosedTab() {
        var open = TravelProtocol.Request.of(TravelProtocol.OPEN);
        assertEquals(open, TravelProtocol.request(TravelProtocol.request(open)));
        var closed = new Tab("wild2", "야생2", "wild2", "world", TravelProtocol.MAP_TERRAIN, "", 0, 0, 0, 0, 0, 0, 0, 0, "", -1, -1, 1, 1, false, "야생2 · 준비 중입니다");
        var s = new State(0, new Position("", 0, 0, 0), List.of(closed), List.of(), "웨이포인트 등록 · 항구 마을");
        assertEquals(s, TravelProtocol.decode(TravelProtocol.encode(s)));
    }

    @Test void link() {
        var t = new TravelLinkProtocol.Transfer(UUID.randomUUID(), 77, "wild2");
        assertEquals(t, TravelLinkProtocol.decode(TravelLinkProtocol.encode(t)));
        var f = new TravelLinkProtocol.Failed(t.player(), 77, TravelLinkProtocol.REFUSED, "정원이 가득 찼어요");
        assertEquals(f, TravelLinkProtocol.decode(TravelLinkProtocol.encode(f)));
        assertThrows(IllegalArgumentException.class, () -> new TravelLinkProtocol.Transfer(t.player(), 1, "bad name"));
    }
}
