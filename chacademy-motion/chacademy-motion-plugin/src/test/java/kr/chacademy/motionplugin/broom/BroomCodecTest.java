package kr.chacademy.motionplugin.broom;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class BroomCodecTest {

    @Test
    void rideRoundTrip() throws Exception {
        var r = new BroomCodec.Ride("broom_7", 1.35f, 0.05f, 10.5f, 0.04f, 0.82f, 0.06f, 0.25f, 0, 120, 60);
        assertEquals(r, BroomCodec.decodeRide(BroomCodec.encodeRide(r)));
    }

    @Test
    void stateRoundTripAndLayout() throws Exception {
        var id = UUID.fromString("00000000-0000-0001-0000-000000000002");
        var s = new BroomCodec.State(id, "broom_1", BroomCodec.STATE_FALL);
        byte[] b = BroomCodec.encodeState(s);
        // long(1) + long(2) + varint(7) + "broom_1" + reason
        assertEquals(8 + 8 + 1 + 7 + 1, b.length);
        assertEquals(1, b[7]);
        assertEquals(2, b[15]);
        assertEquals(7, b[16]);
        assertEquals(BroomCodec.STATE_FALL, b[b.length - 1]);
        assertEquals(s, BroomCodec.decodeState(b));
    }

    @Test
    void perksAreFourBytesBigEndian() throws Exception {
        int mask = BroomCodec.PERK_CORD | BroomCodec.PERK_STORM;
        byte[] b = BroomCodec.encodePerks(mask);
        assertArrayEquals(new byte[]{0, 0, 0, 17}, b);
        assertEquals(mask, BroomCodec.decodePerks(b));
        assertThrows(BroomCodec.MalformedException.class, () -> BroomCodec.decodePerks(new byte[]{1}));
    }

    @Test
    void emptyBroomMeansOff() throws Exception {
        var s = new BroomCodec.State(UUID.randomUUID(), "", BroomCodec.STATE_NORMAL);
        assertEquals("", BroomCodec.decodeState(BroomCodec.encodeState(s)).broom());
    }

    @Test
    void eventIsFiveBytesBigEndianFloat() throws Exception {
        byte[] b = BroomCodec.encodeEvent(new BroomCodec.Event(BroomCodec.EVT_CRASH, 1.0f));
        assertArrayEquals(new byte[]{6, 0x3f, (byte) 0x80, 0, 0}, b);
        assertEquals(1.0f, BroomCodec.decodeEvent(b).value());
    }

    @Test
    void rejectsBadPackets() {
        assertThrows(BroomCodec.MalformedException.class, () -> BroomCodec.decodeEvent(new byte[]{1, 2}));
        assertThrows(BroomCodec.MalformedException.class, () -> BroomCodec.decodeEvent(new byte[]{1, 0x7f, (byte) 0xc0, 0, 0})); // NaN
        assertThrows(BroomCodec.MalformedException.class, () -> BroomCodec.decodeState(new byte[]{1, 2, 3}));
        byte[] ride = BroomCodec.encodeRide(new BroomCodec.Ride("x", 1, 1, 1, 1, 1, 1, 1, 1, 1, 1));
        byte[] longer = java.util.Arrays.copyOf(ride, ride.length + 1);
        assertThrows(BroomCodec.MalformedException.class, () -> BroomCodec.decodeRide(longer));
    }

    @Test
    void speedGuardAllowsLagBurstsButCatchesSustainedOverspeed() {
        var g = new SpeedGuard();
        double cap = 1.35 * 1.45 + 0.6;
        // 렉: 10틱 멈췄다가 한 번에 몰아서 이동
        for (int i = 0; i < 10; i++) assertFalse(g.tick(0, cap));
        assertFalse(g.tick(cap * 10, cap));
        for (int i = 0; i < 200; i++) assertFalse(g.tick(cap * 0.9, cap));
        // 계속 두 배 속도
        boolean caught = false;
        for (int i = 0; i < 200 && !caught; i++) caught = g.tick(cap * 2, cap);
        assertTrue(caught);
    }

    @Test
    void mastery() {
        assertEquals(0f, BroomMastery.of(0, 3600));
        assertEquals(0.5f, BroomMastery.of(1800, 3600));
        assertEquals(1f, BroomMastery.of(99999, 3600));
        assertEquals("견습", BroomMastery.level(0.1f));
        assertEquals("숙련", BroomMastery.level(0.99f));
        assertEquals("마스터", BroomMastery.level(1f));
        assertEquals(1800, BroomMastery.secondsForPercent(50, 3600));
    }

    @Test
    void listAndRequestRoundTrip() throws Exception {
        var m = new BroomCodec.ListMsg("broom_7", 0.4f, java.util.List.of(
                new BroomCodec.Owned("broom_1", "견습생 빗자루", "기본", 0.8f, 0.028f, 8f, 0.03f, 1.3f, 0.045f),
                new BroomCodec.Owned("broom_7", "발걸이 경주용 빗자루", "전설", 1.35f, 0.05f, 10.5f, 0.04f, 0.82f, 0.06f)));
        assertEquals(m, BroomCodec.decodeList(BroomCodec.encodeList(m)));
        var empty = new BroomCodec.ListMsg("", 0f, java.util.List.of());
        assertEquals(empty, BroomCodec.decodeList(BroomCodec.encodeList(empty)));
        var r = new BroomCodec.Request(BroomCodec.REQ_EQUIP, "broom_3");
        assertEquals(r, BroomCodec.decodeRequest(BroomCodec.encodeRequest(r)));
        assertThrows(BroomCodec.MalformedException.class, () -> BroomCodec.decodeRequest(new byte[]{1, 50}));
    }

    @Test
    void buffIs25BytesUuidKindTicksFloat() throws Exception {
        var id = UUID.fromString("00000000-0000-0001-0000-000000000002");
        var b = new BroomCodec.Buff(id, BroomCodec.BUFF_SPEED, 60, 1.2f);
        byte[] raw = BroomCodec.encodeBuff(b);
        assertEquals(25, raw.length);
        assertEquals(BroomCodec.BUFF_SPEED, raw[16]);
        assertEquals(60, raw[20]);
        assertEquals(b, BroomCodec.decodeBuff(raw));
        assertThrows(BroomCodec.MalformedException.class, () -> BroomCodec.decodeBuff(new byte[24]));
    }
}
