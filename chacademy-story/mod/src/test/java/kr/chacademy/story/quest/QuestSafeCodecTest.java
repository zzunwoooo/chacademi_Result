package kr.chacademy.story.quest;

import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 스토리 단계 패킷: 깨진 패킷은 무시(접속 유지), 뒤에 붙은 모르는 필드는 버림, 정상 형식은 그대로 (2026-10-21). */
class QuestSafeCodecTest {
    private static RegistryFriendlyByteBuf buf() { return new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY); }

    @Test void validStepRoundTripsUnchanged() {
        var b = buf();
        var v = new QuestPackets.StepS2C(3, "ch1-8", 2, "1장", "피피", true, List.of(new QuestPackets.Cond("교복", 0, 1)),
                QuestPackets.TARGET_LOCATION, "minecraft:overworld", 1, 2, 3, 1.5, "");
        QuestPackets.StepS2C.CODEC.encode(b, v);
        var back = QuestPackets.StepS2C.CODEC.decode(b);
        assertEquals(v, back);
        assertEquals(0, b.readableBytes());
    }

    @Test void truncatedStepIsIgnoredWithoutThrowing() {
        var b = buf();
        b.writeVarInt(1);
        b.writeUtf("ch1-8");
        assertSame(QuestPackets.StepS2C.INVALID, QuestPackets.StepS2C.CODEC.decode(b));
        assertEquals(0, b.readableBytes());
    }

    @Test void tooManyConditionsIsIgnored() {
        var b = buf();
        b.writeVarInt(1); b.writeUtf("ch1-8"); b.writeVarInt(1); b.writeBoolean(true);
        b.writeVarInt(QuestPackets.MAX_CONDS + 1);
        assertSame(QuestPackets.UpdateS2C.INVALID, QuestPackets.UpdateS2C.CODEC.decode(b));
        assertEquals(0, b.readableBytes());
    }

    @Test void trailingFieldsFromNewerServerAreSkipped() {
        var b = buf();
        QuestPackets.UpdateS2C.CODEC.encode(b, new QuestPackets.UpdateS2C(1, "ch1-8", 1, false, new int[]{1, 2}));
        b.writeInt(42);
        var u = QuestPackets.UpdateS2C.CODEC.decode(b);
        assertArrayEquals(new int[]{1, 2}, u.current());
        assertEquals(0, b.readableBytes());
    }
}
