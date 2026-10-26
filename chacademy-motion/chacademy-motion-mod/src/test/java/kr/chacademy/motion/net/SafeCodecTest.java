package kr.chacademy.motion.net;

import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SafeCodecTest {
    private static RegistryFriendlyByteBuf buf() { return new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY); }

    @Test void truncatedRideIsIgnoredWithoutThrowing() {
        var b = buf();
        ByteBufCodecs.STRING_UTF8.encode(b, "oak");
        b.writeFloat(1f);
        assertSame(BroomPackets.RideS2C.INVALID, BroomPackets.RideS2C.CODEC.decode(b));
        assertEquals(0, b.readableBytes());
    }

    @Test void trailingFieldsFromNewerServerAreSkipped() {
        var b = buf();
        BroomPackets.FxS2C.CODEC.encode(b, new BroomPackets.FxS2C((byte) 2));
        b.writeInt(42);
        assertEquals(2, BroomPackets.FxS2C.CODEC.decode(b).kind());
        assertEquals(0, b.readableBytes());
    }

    @Test void oversizedBroomListIsIgnored() {
        var b = buf();
        ByteBufCodecs.STRING_UTF8.encode(b, "");
        b.writeFloat(0);
        ByteBufCodecs.VAR_INT.encode(b, 65);
        assertSame(BroomPackets.ListS2C.INVALID, BroomPackets.ListS2C.CODEC.decode(b));
    }
}
