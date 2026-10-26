package kr.chacademy.storyplugin;

import io.netty.buffer.Unpooled;
import java.util.List;
import kr.chacademy.story.quest.QuestPackets;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class QuestWireCompatibilityTest {
    private static RegistryFriendlyByteBuf buffer(byte[] bytes) {
        return new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(bytes), RegistryAccess.EMPTY);
    }
    @Test void serverStepDecodesInClientWithUnicodeLocationAndNpc() {
        String npc="12345678-1234-1234-1234-123456789abc";
        var server=new QuestCodec.StepView(128,"ch1-6",129,"1장","선착장 포탈",true,
                List.of(new QuestCodec.CondView("교복",127,128)),QuestCodec.TARGET_LOCATION,"world",1.25,64,-12.5,2,npc);
        var b=buffer(QuestCodec.step(server));
        var client=QuestPackets.StepS2C.CODEC.decode(b);
        assertEquals(new QuestPackets.StepS2C(128,"ch1-6",129,"1장","선착장 포탈",true,
                List.of(new QuestPackets.Cond("교복",127,128)),QuestPackets.TARGET_LOCATION,"world",1.25,64,-12.5,2,npc),client);
        assertEquals(0,b.readableBytes());
        b.release();
    }
    @Test void serverUpdateAndHiddenStepDecodeInClient() {
        var b=buffer(QuestCodec.update(128,"ch1-19",129,false,new int[]{0,127,128,10000}));
        var client=QuestPackets.UpdateS2C.CODEC.decode(b);
        assertEquals(128,client.session()); assertEquals("ch1-19",client.step());
        assertEquals(129,client.run()); assertFalse(client.ready());
        assertArrayEquals(new int[]{0,127,128,10000},client.current()); assertEquals(0,b.readableBytes()); b.release();
        var empty=buffer(QuestCodec.step(new QuestCodec.StepView(1,"",0,"","",false,List.of(),0,"",0,0,0,0,"")));
        assertEquals("",QuestPackets.StepS2C.CODEC.decode(empty).step()); assertEquals(0,empty.readableBytes()); empty.release();
    }
    @Test void serverGuideDecodesAndConsumesFutureTail() {
        for(int session:new int[]{0,1,127,128,16384,Integer.MAX_VALUE}){
            var b=new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);b.writeBytes(QuestCodec.guide(session));b.writeInt(42);
            assertEquals(session,QuestPackets.GuideS2C.CODEC.decode(b).session());assertEquals(0,b.readableBytes());b.release();
        }
    }
    @Test void malformedGuideNeverDisconnectsTheClient() {
        for(byte[] bytes:new byte[][]{new byte[0],new byte[]{(byte)128},new byte[]{(byte)255,(byte)255,(byte)255,(byte)255,15}}){
            var b=buffer(bytes);assertSame(QuestPackets.GuideS2C.INVALID,QuestPackets.GuideS2C.CODEC.decode(b));assertEquals(0,b.readableBytes());b.release();
        }
    }
    @Test void serverPointsDecodeAndClearWithoutLosingGuideWireSafety() {
        var points=List.of(new QuestCodec.Point("minecraft:overworld",1,64,2),new QuestCodec.Point("minecraft:overworld",20,70,-3));
        var b=buffer(QuestCodec.points(7,points));var packet=QuestPackets.PointsS2C.CODEC.decode(b);
        assertEquals(7,packet.session());assertEquals(2,packet.points().size());assertEquals(20,packet.points().get(1).x());assertEquals(0,b.readableBytes());b.release();
        var clear=buffer(QuestCodec.points(7,List.of()));assertEquals(List.of(),QuestPackets.PointsS2C.CODEC.decode(clear).points());assertEquals(0,clear.readableBytes());clear.release();
        byte[] bytes=QuestCodec.points(7,points);
        for(int size=0;size<bytes.length;size++){
            var cut=buffer(java.util.Arrays.copyOf(bytes,size));assertSame(QuestPackets.PointsS2C.INVALID,QuestPackets.PointsS2C.CODEC.decode(cut));assertEquals(0,cut.readableBytes());cut.release();
        }
        var invalid=buffer(QuestCodec.points(7,List.of(new QuestCodec.Point("minecraft:overworld",Double.NaN,1,2))));
        assertSame(QuestPackets.PointsS2C.INVALID,QuestPackets.PointsS2C.CODEC.decode(invalid));assertEquals(0,invalid.readableBytes());invalid.release();
    }

    @Test void serverGuideTargetDecodesAndRejectsBadValues() {
        String npc="12345678-1234-1234-1234-123456789abc";
        var b=buffer(QuestCodec.guideTarget(130,QuestCodec.FOLLOW_LIVE,true,"minecraft:overworld",12.5,64,-3.25,npc));
        assertEquals(new QuestPackets.GuideTargetS2C(130,QuestPackets.FOLLOW_LIVE,true,"minecraft:overworld",12.5,64,-3.25,npc),QuestPackets.GuideTargetS2C.CODEC.decode(b));
        assertEquals(0,b.readableBytes());b.release();
        assertEquals(QuestCodec.FOLLOW_OTHER_WORLD,QuestPackets.FOLLOW_OTHER_WORLD);assertEquals(QuestCodec.FOLLOW_UNLOADED,QuestPackets.FOLLOW_UNLOADED);
        for(byte[] bad:new byte[][]{QuestCodec.guideTarget(0,0,false,"w",0,0,0,""),QuestCodec.guideTarget(3,7,false,"w",0,0,0,""),QuestCodec.guideTarget(3,0,false,"w",Double.NaN,0,0,"")}){
            var x=buffer(bad);assertSame(QuestPackets.GuideTargetS2C.INVALID,QuestPackets.GuideTargetS2C.CODEC.decode(x));assertEquals(0,x.readableBytes());x.release();
        }
    }

}
