package school.magiccodex.protocol;

import java.io.*;
import java.util.*;

/**
 * 동행 펫 (오너 2026-10-26, 메모 #81): MCPets 몹을 소환하지 않고, 서버는 "누가 어떤 펫을 꺼냈는지"만 알려 주고
 * 모든 클라가 각자 펫 모델(config/magiccodex/pets/models/&lt;펫ID&gt;.bbmodel)을 작게 그려 주인 주변을 걷거나 날게 한다.
 * 서버 → 클라 하나뿐 (채널 {@link #CHANNEL}). 그 플레이어가 있는 세계의 펫 전부를 한 번에 보낸다
 * (접속·세계 이동·누가 펫을 꺼내거나 넣을 때만).
 * <pre>
 * u8 VERSION · UTF world · u16 n · n × PET
 * PET = i32 ownerEntityId · UTF petId · u8 stars(1~3) · u8 motion · f32 scale · u8 flags
 * </pre>
 * motion: 0 땅(주인 주변을 걸어다님) · 1 날기(어깨 높이에서 둥둥 떠서 돎) · 2 물(물속이면 헤엄, 밖이면 둥둥).
 * scale: 모델 크기 배율(0.05~2). flags: bit0 = 방금 능력 발동(클라가 짧게 반짝이거나 모션 재생, 없어도 됨).
 */
public final class PetCompanionProtocol {
    public static final String CHANNEL = "magiccodex:pet_companions";
    public static final int VERSION = 1, MAX_PETS = 256, MAX_BYTES = 32_000;
    public static final int GROUND = 0, FLY = 1, SWIM = 2;
    public static final int FLAG_CAST = 1;

    public record Pet(int owner, String id, int stars, int motion, float scale, int flags) {
        public Pet {
            if (!PetProtocol.validId(id) || stars < 1 || stars > 3 || motion < 0 || motion > 2
                    || !Float.isFinite(scale) || scale < 0.05f || scale > 2f || flags < 0 || flags > 255)
                throw new IllegalArgumentException("pet");
        }
    }
    public record Pets(String world, List<Pet> pets) {
        public Pets {
            Objects.requireNonNull(world);
            pets = List.copyOf(pets);
            if (world.length() > 128 || pets.size() > MAX_PETS) throw new IllegalArgumentException("pets");
        }
        public static final Pets EMPTY = new Pets("", List.of());
    }

    public static byte[] encode(Pets m) {
        try {
            var b = new ByteArrayOutputStream(); var o = new DataOutputStream(b);
            o.writeByte(VERSION); o.writeUTF(m.world()); o.writeShort(m.pets().size());
            for (var p : m.pets()) {
                o.writeInt(p.owner()); o.writeUTF(p.id()); o.writeByte(p.stars()); o.writeByte(p.motion());
                o.writeFloat(p.scale()); o.writeByte(p.flags());
            }
            if (b.size() > MAX_BYTES) throw new IllegalArgumentException("size");
            return b.toByteArray();
        } catch (IOException e) { throw new IllegalArgumentException(e); }
    }

    public static Pets decode(byte[] bytes) {
        if (bytes == null || bytes.length < 4 || bytes.length > MAX_BYTES) throw new IllegalArgumentException("size");
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readUnsignedByte() != VERSION) throw new IOException("version");
            String world = in.readUTF();
            int n = in.readUnsignedShort();
            if (n > MAX_PETS) throw new IOException("count");
            var out = new ArrayList<Pet>(n);
            for (int i = 0; i < n; i++)
                out.add(new Pet(in.readInt(), in.readUTF(), in.readUnsignedByte(), in.readUnsignedByte(), in.readFloat(), in.readUnsignedByte()));
            if (in.available() != 0) throw new IOException("trailing");
            return new Pets(world, out);
        } catch (IOException | RuntimeException e) { throw new IllegalArgumentException(e); }
    }

    private PetCompanionProtocol() {}
}
