package school.magiccodex.protocol;

import java.io.*;
import java.util.Locale;

/**
 * 마법 연습장 훈련 허수아비 피해 표시 (서버 MagicMythic → 클라 MagicCodex), 오픈 전 메모 #107 (2026-10-26).
 * 허수아비가 맞을 때만, 근처(기본 32블록) 플레이어에게 한 번 보낸다. 매 틱 보내는 것 없음.
 * 클라는 머리 위에 "누적 1,234"(+작은 초당 피해) 줄과, 맞을 때마다 떠올랐다 사라지는 피해 숫자(메이플 데미지 스킨 식)를 그린다.
 * 누적은 resetTicks 동안 안 맞으면 클라에서도 스스로 지운다(서버도 같은 시간에 0부터 다시 센다).
 *
 * v1 프레임: byte version=1, int id(허수아비 몸 엔티티 id), double x, double y, double z(표시 기준점 = 모델 머리 꼭대기),
 *           float amount(이번 피해), byte kind, float total(누적), float dps(초당), short resetTicks
 */
public final class TrainingDummyProtocol {
    public static final String CHANNEL = "magiccodex:training_dummy";
    public static final int VERSION = 1;
    public static final int MAX_BYTES = 64;
    public static final float MAX_AMOUNT = 1.0e9f;

    /** 숫자 모양 구분. 서버에 치명타 개념이 없어 "큰 피해"(설정 heavy-damage 이상)를 치명타 자리에 쓴다. */
    public enum Kind { NORMAL, HEAVY, REACTION, DOT }

    public record Hit(int id, double x, double y, double z, float amount, Kind kind, float total, float dps, int resetTicks) {
        public Hit {
            if (kind == null) throw new IllegalArgumentException("kind");
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) throw new IllegalArgumentException("pos");
            amount = clean(amount); total = clean(total); dps = clean(dps);
            resetTicks = Math.clamp(resetTicks, 20, 20 * 120);
        }
        private static float clean(float v) { return Float.isFinite(v) ? Math.clamp(v, 0, MAX_AMOUNT) : 0; }
    }

    private TrainingDummyProtocol() {}

    /**
     * 화면에 쓰는 숫자: 10 미만의 소수는 한 자리("3.5"), 나머지는 반올림한 정수에 쉼표("1,234"). 음수·NaN 은 0.
     */
    public static String format(double v) {
        if (!Double.isFinite(v) || v <= 0) return "0";
        if (v < 10) {
            double one = Math.round(v * 10) / 10.0;
            if (one < 10 && one != Math.rint(one)) return String.format(Locale.ROOT, "%.1f", one);
        }
        return String.format(Locale.ROOT, "%,d", Math.round(v));
    }

    public static byte[] encode(Hit h) {
        try {
            var b = new ByteArrayOutputStream(48);
            var o = new DataOutputStream(b);
            o.writeByte(VERSION);
            o.writeInt(h.id());
            o.writeDouble(h.x()); o.writeDouble(h.y()); o.writeDouble(h.z());
            o.writeFloat(h.amount()); o.writeByte(h.kind().ordinal());
            o.writeFloat(h.total()); o.writeFloat(h.dps());
            o.writeShort(h.resetTicks());
            if (b.size() > MAX_BYTES) throw new IOException("size");
            return b.toByteArray();
        } catch (IOException e) { throw new IllegalArgumentException(e); }
    }

    public static Hit decode(byte[] raw) {
        try {
            if (raw == null || raw.length > MAX_BYTES) throw new IOException("size");
            var i = new DataInputStream(new ByteArrayInputStream(raw));
            if (i.readUnsignedByte() != VERSION) throw new IOException("version");
            int id = i.readInt();
            double x = i.readDouble(), y = i.readDouble(), z = i.readDouble();
            float amount = i.readFloat();
            int kind = i.readUnsignedByte();
            if (kind >= Kind.values().length) throw new IOException("kind");
            float total = i.readFloat(), dps = i.readFloat();
            int reset = i.readUnsignedShort();
            if (i.available() != 0) throw new IOException("trailing");
            return new Hit(id, x, y, z, amount, Kind.values()[kind], total, dps, reset);
        } catch (IOException | RuntimeException e) { throw new IllegalArgumentException(e); }
    }
}
