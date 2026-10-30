package school.chacademia.dungeon;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import school.chacademia.dungeon.Instances.State;
import school.chacademia.dungeon.protocol.DungeonProtocol;

class InstancesTest {
    private final Instances in = new Instances();
    private final AtomicInteger session = new AtomicInteger();
    private static final String D = "lich_lab";

    private Instances.Slot enterDirect(UUID p) {
        var s = in.reserveDirect(D, p);
        if (s != null) s.state = State.RUNNING;
        return s;
    }

    @Test void threeEnterFourthQueues() {
        in.ensure(D, 3);
        var a = UUID.randomUUID(); var b = UUID.randomUUID(); var c = UUID.randomUUID(); var d = UUID.randomUUID();
        var sa = enterDirect(a); var sb = enterDirect(b); var sc = enterDirect(c);
        assertNotNull(sa); assertNotNull(sb); assertNotNull(sc);
        assertEquals(3, List.of(sa.no, sb.no, sc.no).stream().distinct().count(), "칸 3개가 서로 달라야 함");
        assertNull(in.reserveDirect(D, d), "4번째는 바로 못 들어감");
        assertTrue(in.join(D, d));
        assertEquals(1, in.position(D, d));
        assertEquals(3, in.busy(D));
        assertTrue(in.assign(D, 0, 60_000, x -> true, session::incrementAndGet).isEmpty(), "빈 칸이 없으면 안내 없음");

        // 한 명이 나가면 정리 중(RESETTING) 동안은 배정하지 않는다
        Instances.toResetting(sb);
        assertTrue(in.assign(D, 0, 60_000, x -> true, session::incrementAndGet).isEmpty(), "정리 끝나기 전엔 배정 안 함");
        sb.state = State.FREE; // 정리 완료
        var offers = in.assign(D, 0, 60_000, x -> true, session::incrementAndGet);
        assertEquals(1, offers.size());
        assertEquals(d, offers.get(0).player());
        assertSame(sb, offers.get(0).slot());
        assertEquals(State.RESERVED, sb.state);
        assertEquals(0, in.position(D, d), "안내를 받으면 대기 번호에서 빠짐");
        assertNotNull(in.offer(d));
    }

    @Test void duplicateJoinRefused() {
        in.ensure(D, 3);
        var a = UUID.randomUUID();
        assertTrue(in.join(D, a));
        assertFalse(in.join(D, a), "같은 사람 중복 등록 불가");
        assertFalse(in.join("other", a), "다른 던전에도 동시에 등록 불가");
        assertEquals(1, in.queued(D));
    }

    @Test void directEntryWaitsForQueue() {
        in.ensure(D, 1);
        var a = UUID.randomUUID(); var b = UUID.randomUUID(); var c = UUID.randomUUID();
        enterDirect(a);
        assertTrue(in.join(D, b));
        in.slot(D, 1).state = State.FREE;
        assertNull(in.reserveDirect(D, c), "대기자가 있으면 포탈에서 바로 입장 불가 (순서 지킴)");
    }

    @Test void offerExpiresAndOfflineSkipped() {
        in.ensure(D, 1);
        var gone = UUID.randomUUID(); var next = UUID.randomUUID();
        in.join(D, gone); in.join(D, next);
        var offers = in.assign(D, 1000, 60_000, id -> !id.equals(gone), session::incrementAndGet);
        assertEquals(next, offers.get(0).player(), "접속 안 한 사람은 건너뜀");
        assertTrue(in.expired(30_000).isEmpty());
        var expired = in.expired(61_000);
        assertEquals(1, expired.size());
        assertNull(in.offer(next));
    }

    @Test void leaveReturnsReservedSlot() {
        in.ensure(D, 1);
        var a = UUID.randomUUID();
        in.join(D, a);
        var offer = in.assign(D, 0, 60_000, x -> true, session::incrementAndGet).get(0);
        assertSame(offer.slot(), in.leave(a), "대기 취소하면 잡아 둔 칸을 돌려줌");
        assertNull(in.queuedDungeon(a));
    }

    @Test void protocolRoundTrip() throws Exception {
        var holder = new DungeonProtocol.Holder(123456, "별빛", new DungeonProtocol.Title("손발맞는", 0x96E4EC), new DungeonProtocol.Title("용사", 0xDEC58E));
        var groups = List.of(new DungeonProtocol.RewardGroup(DungeonProtocol.R_BASE, "기본 보상", false, List.of(new byte[]{1, 2, 3}), List.of()),
                new DungeonProtocol.RewardGroup(DungeonProtocol.R_RANDOM, "확률 보상", false, List.of(new byte[]{4}, new byte[]{5}), List.of(1250, 10000)));
        var entry = new DungeonProtocol.Entry(7, "리치의 지하 연구실", "부제", true, 99_000, holder, 2, 3, 1, 4, "던전 입장권",
                true, DungeonProtocol.BTN_ENTER, "", true, groups);
        var back = (DungeonProtocol.EntryMsg) DungeonProtocol.decodeToClient(DungeonProtocol.encode(new DungeonProtocol.EntryMsg(entry)));
        assertEquals("별빛", back.entry().best().name());
        assertEquals("용사", back.entry().best().suffix().name());
        assertEquals(3, back.entry().rewards().get(0).items().get(0).length);
        assertEquals(List.of(1250, 10000), back.entry().rewards().get(1).chances());
        assertTrue(back.entry().ticketFree() && back.entry().noRegen());
        var run = new DungeonProtocol.Run(true, "던전", 5000, true, false, 2, 3, "단계", "목표", 1, 4, true);
        assertEquals(run, ((DungeonProtocol.RunMsg) DungeonProtocol.decodeToClient(DungeonProtocol.encode(new DungeonProtocol.RunMsg(run)))).run());
        var action = DungeonProtocol.decodeAction(DungeonProtocol.encode(new DungeonProtocol.Action(DungeonProtocol.C_ENTER, 42)));
        assertEquals(42, action.session());
        assertThrows(java.io.IOException.class, () -> DungeonProtocol.decodeAction(new byte[]{1, 99, 0, 0, 0, 0}));
    }

    @Test void copySlotShifts() {
        var a = new Defs.Slot(1);
        a.start = new Defs.Loc("dungeon1", 0.5, 65, 0.5, 90, 0);
        a.corner1 = new int[]{-8, 46, -95}; a.corner2 = new int[]{175, 110, 89};
        var room = new Defs.Room("1번방"); room.corner1 = new int[]{0, 60, 0}; room.corner2 = new int[]{10, 70, 10};
        room.zones.add(new Defs.Zone(new int[]{1, 61, 1}, new int[]{9, 61, 9}, "CA_Mob_ArchlichThrall", 6));
        a.rooms.put(room.name, room);
        var door = new Defs.Door("1-1 door", new int[]{11, 61, 4}, new int[]{11, 64, 6}); door.gimmick = "화로";
        a.doors.put(door.name, door);
        a.gimmicks.put("화로", new int[]{5, 61, 5});
        var b = new Defs.Slot(2);
        b.start = new Defs.Loc("dungeon1", 300.5, 65, 0.5, 90, 0);
        Defs.copySlot(a, b, 300, 0, 0);
        assertEquals(300.5, b.start.x(), 1e-9, "복사해도 대상 칸 시작점은 그대로");
        assertArrayEquals(new int[]{292, 46, -95}, b.corner1);
        assertArrayEquals(new int[]{301, 61, 1}, b.rooms.get("1번방").zones.get(0).corner1);
        assertArrayEquals(new int[]{311, 61, 4}, b.doors.get("1-1 door").corner1);
        assertEquals("화로", b.doors.get("1-1 door").gimmick);
        assertArrayEquals(new int[]{305, 61, 5}, b.gimmicks.get("화로"));
        assertTrue(Defs.validName("1-1 door") && Defs.validName("입구방") && !Defs.validName("a.b") && !Defs.validName(" "));
    }

    @Test void boxContains() {
        var box = Defs.Box.of(new int[]{10, 5, 10}, new int[]{0, 0, 0});
        assertTrue(box.contains(0, 0, 0));
        assertTrue(box.contains(10.9, 5.5, 10.9));
        assertFalse(box.contains(11.01, 1, 1));
        assertEquals(new Defs.Loc("dungeon1", 1.5, 64, -3.25, 90, 0), Defs.Loc.parse(new Defs.Loc("dungeon1", 1.5, 64, -3.25, 90, 0).text()));
    }
}
