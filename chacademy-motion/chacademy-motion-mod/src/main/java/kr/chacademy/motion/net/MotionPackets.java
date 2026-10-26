package kr.chacademy.motion.net;

import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * 서버 플러그인과 주고받는 패킷.
 *
 * <p>바이트 형식은 플러그인의 {@code MotionCodec} 과 반드시 같아야 한다.
 * <ul>
 *   <li>문자열: VarInt(바이트 길이) + UTF-8 바이트</li>
 *   <li>UUID: long(상위 64비트) + long(하위 64비트), 빅엔디언</li>
 * </ul>
 */
public final class MotionPackets {
    public static final String NAMESPACE = "chacademy";

    private MotionPackets() {}

    /** 클라 → 서버: "내가 이 모션으로 시전했어". */
    public record CastC2S(String animation) implements CustomPacketPayload {
        public static final Type<CastC2S> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(NAMESPACE, "cast"));
        public static final StreamCodec<RegistryFriendlyByteBuf, CastC2S> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, CastC2S::animation,
                CastC2S::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 서버 → 클라: "이 플레이어가 이 모션을 재생해". */
    public record PlayAnimS2C(UUID player, String animation) implements CustomPacketPayload {
        public static final Type<PlayAnimS2C> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(NAMESPACE, "anim"));
        /** 깨진·모르는 형식의 패킷 (핸들러가 무시). */
        public static final PlayAnimS2C INVALID = new PlayAnimS2C(new UUID(0, 0), "");
        public static final StreamCodec<RegistryFriendlyByteBuf, PlayAnimS2C> CODEC = SafeCodec.failSafe("anim", StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, PlayAnimS2C::player,
                ByteBufCodecs.STRING_UTF8, PlayAnimS2C::animation,
                PlayAnimS2C::new), INVALID);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
