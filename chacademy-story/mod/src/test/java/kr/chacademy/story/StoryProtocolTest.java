package kr.chacademy.story;
import org.junit.jupiter.api.Test;
import io.netty.buffer.Unpooled;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.core.RegistryAccess;
import kr.chacademy.cutscene.net.CutscenePackets;
import kr.chacademy.storyplugin.StoryCodec;
import kr.chacademy.story.dialogue.TextVars;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
class StoryProtocolTest {
    static RegistryFriendlyByteBuf buffer(){return new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);}
    static byte[] bytes(RegistryFriendlyByteBuf b){byte[] a=new byte[b.readableBytes()];b.readBytes(a);b.release();return a;}
    @Test void clientEventMatchesServerCodec(){
        var b=buffer();CutscenePackets.DialogueEventC2S.CODEC.encode(b,new CutscenePackets.DialogueEventC2S("ch1_wakeup","c_wake_1","teacher",-2));
        assertEquals(new StoryCodec.DialogueEvent("ch1_wakeup","c_wake_1","teacher",-2),StoryCodec.dialogueEvent(bytes(b)));
    }
    @Test void clientProgressMatchesServerCodec(){
        var b=buffer();CutscenePackets.DialogueProgressC2S.CODEC.encode(b,new CutscenePackets.DialogueProgressC2S("ch1_wakeup","wake",3));
        assertEquals(new StoryCodec.DialogueProgress("ch1_wakeup","wake",3),StoryCodec.dialogueProgress(bytes(b)));
    }
    @Test void serverOpenPreservesKoreanNicknameAndCheckpoint(){
        String vars=StoryCodec.encodeVars(Map.of("{player}","별빛","{account}","Account"));
        var b=new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(StoryCodec.dialogueOpen("ch1_wakeup","teacher=37",vars,"wake",3)),RegistryAccess.EMPTY);
        try{
            var v=CutscenePackets.DialogueOpenS2C.CODEC.decode(b);
            assertEquals("wake",v.startScene());assertEquals(3,v.startLine());
            assertEquals("별빛 / Account",TextVars.fill("{player} / {account}",TextVars.decode(v.vars())));
            assertEquals(0,b.readableBytes());
        }finally{b.release();}
    }
}
