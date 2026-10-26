package kr.chacademy.storyplugin;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;
class StoryCodecTest {
    static byte[] event(){var b=new ByteArrayOutputStream();for(String s:new String[]{"ch1_wakeup","c_wake_1","teacher"})StoryCodec.writeString(b,s);b.writeBytes(new byte[]{0,0,0,2});return b.toByteArray();}
    @Test void eventDecodesAndTruncationIsRejected(){
        byte[] bytes=event();assertEquals(new StoryCodec.DialogueEvent("ch1_wakeup","c_wake_1","teacher",2),StoryCodec.dialogueEvent(bytes));
        for(int i=0;i<bytes.length;i++){int size=i;assertThrows(IllegalArgumentException.class,()->StoryCodec.dialogueEvent(Arrays.copyOf(bytes,size)));}
    }
    @Test void trailingBytesAndOversizedStringsAreRejected(){
        assertThrows(IllegalArgumentException.class,()->StoryCodec.dialogueEvent(Arrays.copyOf(event(),event().length+1)));
        var b=new ByteArrayOutputStream();StoryCodec.writeString(b,"x".repeat(257));
        assertThrows(IllegalArgumentException.class,()->StoryCodec.dialogueDone(b.toByteArray()));
    }
    @Test void progressRoundTrip(){
        var b=new ByteArrayOutputStream();StoryCodec.writeString(b,"ch1_wakeup");StoryCodec.writeString(b,"wake");StoryCodec.writeVarInt(b,12);
        assertEquals(new StoryCodec.DialogueProgress("ch1_wakeup","wake",12),StoryCodec.dialogueProgress(b.toByteArray()));
    }
    @Test void failAndAbortPacketsDecode(){
        var b=new ByteArrayOutputStream();StoryCodec.writeVarInt(b,StoryCodec.KIND_DIALOGUE);StoryCodec.writeString(b,"ch1_wakeup");StoryCodec.writeString(b,"missing");
        assertEquals(new StoryCodec.StoryFail(1,"ch1_wakeup","missing"),StoryCodec.storyFail(b.toByteArray()));
        assertThrows(IllegalArgumentException.class,()->StoryCodec.storyFail(Arrays.copyOf(b.toByteArray(),b.size()-1)));
        var a=new ByteArrayOutputStream();StoryCodec.writeString(a,"ch1_ashen_night");
        assertEquals(new StoryCodec.CutsceneAbort("ch1_ashen_night"),StoryCodec.cutsceneAbort(a.toByteArray()));
        assertThrows(IllegalArgumentException.class,()->StoryCodec.cutsceneAbort(Arrays.copyOf(a.toByteArray(),a.size()+1)));
    }
    @Test void clientProtocolIsReadFromListeningChannels(){
        assertEquals(0,StoryCodec.clientProtocol(java.util.List.of("minecraft:brand","other:channel")));
        assertEquals(1,StoryCodec.clientProtocol(java.util.List.of(StoryCodec.CUTSCENE_PLAY,StoryCodec.DIALOGUE_OPEN)));   // 버전 채널이 없던 예전 모드
        assertEquals(2,StoryCodec.clientProtocol(java.util.List.of(StoryCodec.CUTSCENE_PLAY,"chacademy:story_v2")));
        assertEquals(3,StoryCodec.clientProtocol(java.util.List.of("chacademy:story_v2","chacademy:story_v3")));
        assertEquals(0,StoryCodec.clientProtocol(java.util.List.of("chacademy:story_vX","chacademy:story_v","chacademy:story_v99999999999")));
        assertEquals("chacademy:story_v"+StoryCodec.PROTOCOL,StoryCodec.STORY_HELLO);
        assertArrayEquals(new byte[]{(byte)StoryCodec.PROTOCOL},StoryCodec.hello(StoryCodec.PROTOCOL));
    }
    @Test void clientStringsFollowMinecraftStringUtf8Limit256(){
        var ok=new ByteArrayOutputStream();StoryCodec.writeString(ok,"가".repeat(256));   // 256자 = 768바이트까지 허용
        assertEquals("가".repeat(256),StoryCodec.cutsceneAbort(ok.toByteArray()).id());
        var tooLong=new ByteArrayOutputStream();StoryCodec.writeString(tooLong,"가".repeat(257));
        assertThrows(IllegalArgumentException.class,()->StoryCodec.cutsceneAbort(tooLong.toByteArray()));
    }
}
