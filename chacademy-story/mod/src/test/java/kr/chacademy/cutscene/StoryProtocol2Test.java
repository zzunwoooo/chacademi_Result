package kr.chacademy.cutscene;
import org.junit.jupiter.api.Test;
import io.netty.buffer.Unpooled;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.core.RegistryAccess;
import kr.chacademy.cutscene.net.CutscenePackets;
import kr.chacademy.storyplugin.StoryCodec;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
/** 프로토콜 2 의 새 패킷이 플러그인 StoryCodec 과 바이트까지 같은지, 버전 숫자·문자열 한도가 양쪽에서 같은지. */
class StoryProtocol2Test {
    static RegistryFriendlyByteBuf buffer(){return new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);}
    static byte[] bytes(RegistryFriendlyByteBuf b){byte[] a=new byte[b.readableBytes()];b.readBytes(a);b.release();return a;}
    @Test void versionAndLimitsMatchThePlugin(){
        assertEquals(StoryCodec.PROTOCOL,CutscenePackets.PROTOCOL);
        assertEquals(StoryCodec.MAX_STRING,CutscenePackets.MAX_C2S_STRING);
        assertEquals(StoryCodec.STORY_HELLO,CutscenePackets.HelloS2C.TYPE.id().toString());
        assertEquals(StoryCodec.CUTSCENE_ABORT,CutscenePackets.AbortC2S.TYPE.id().toString());
        assertEquals(StoryCodec.STORY_FAIL,CutscenePackets.StoryFailC2S.TYPE.id().toString());
        assertEquals(StoryCodec.KIND_CUTSCENE,CutscenePackets.KIND_CUTSCENE);assertEquals(StoryCodec.KIND_DIALOGUE,CutscenePackets.KIND_DIALOGUE);
        // 서버는 모드가 듣는 채널 이름으로 버전을 안다
        assertEquals(CutscenePackets.PROTOCOL,StoryCodec.clientProtocol(List.of(CutscenePackets.PlayS2C.TYPE.id().toString(),CutscenePackets.HelloS2C.TYPE.id().toString())));
        assertTrue(StoryCodec.clientProtocol(List.of(CutscenePackets.PlayS2C.TYPE.id().toString()))<StoryCodec.MIN_CLIENT_PROTOCOL);
    }
    @Test void serverHelloIsReadByTheMod(){
        var b=new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(StoryCodec.hello(StoryCodec.PROTOCOL)),RegistryAccess.EMPTY);
        try{assertEquals(StoryCodec.PROTOCOL,CutscenePackets.HelloS2C.CODEC.decode(b).protocol());assertEquals(0,b.readableBytes());}finally{b.release();}
    }
    @Test void abortAndFailMatchServerCodec(){
        var a=buffer();CutscenePackets.AbortC2S.CODEC.encode(a,new CutscenePackets.AbortC2S("ch1_ashen_night"));
        assertEquals(new StoryCodec.CutsceneAbort("ch1_ashen_night"),StoryCodec.cutsceneAbort(bytes(a)));
        var f=buffer();CutscenePackets.StoryFailC2S.CODEC.encode(f,new CutscenePackets.StoryFailC2S(CutscenePackets.KIND_DIALOGUE,"ch1_wakeup",CutscenePackets.FAIL_MISSING));
        assertEquals(new StoryCodec.StoryFail(StoryCodec.KIND_DIALOGUE,"ch1_wakeup","missing"),StoryCodec.storyFail(bytes(f)));
        var d=buffer();CutscenePackets.DoneC2S.CODEC.encode(d,new CutscenePackets.DoneC2S("ch1_ashen_night",true));
        assertEquals(new StoryCodec.CutsceneDone("ch1_ashen_night",true),StoryCodec.cutsceneDone(bytes(d)));
    }
    @Test void overlongClientStringsAreClippedSoTheServerNeverRejectsThePacket(){
        String longKorean="가".repeat(1000);
        var p=new CutscenePackets.StoryFailC2S(CutscenePackets.KIND_CUTSCENE,"x",longKorean);
        assertEquals(CutscenePackets.MAX_C2S_STRING,p.reason().length());
        var b=buffer();CutscenePackets.StoryFailC2S.CODEC.encode(b,p);
        assertEquals("가".repeat(256),StoryCodec.storyFail(bytes(b)).reason());   // 256자 = 768바이트: 서버 한도와 같다
        var e=buffer();CutscenePackets.DialogueDoneC2S.CODEC.encode(e,new CutscenePackets.DialogueDoneC2S("x".repeat(300),null));
        assertEquals(new StoryCodec.DialogueDone("x".repeat(256),""),StoryCodec.dialogueDone(bytes(e)));
    }
    @Test void catalogAndReloadMatchAcrossClientAndServer() {
        var b=buffer();CutscenePackets.CatalogC2S.CODEC.encode(b,new CutscenePackets.CatalogC2S("intro","ch1-1\nch1-2"));
        var catalog=StoryCodec.catalog(bytes(b));assertEquals(List.of("intro"),catalog.cutscenes());assertEquals(List.of("ch1-1","ch1-2"),catalog.dialogues());
        for(int kind:List.of(0,1)) {
            var r=new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(StoryCodec.reloadContent(kind)),RegistryAccess.EMPTY);
            try{assertEquals(kind,CutscenePackets.ReloadS2C.CODEC.decode(r).kind());assertEquals(0,r.readableBytes());}finally{r.release();}
        }
        assertEquals(StoryCodec.STORY_CATALOG,CutscenePackets.CatalogC2S.TYPE.id().toString());
        assertEquals(StoryCodec.STORY_RELOAD,CutscenePackets.ReloadS2C.TYPE.id().toString());
    }
    @Test void authorizedSessionAndIdOnlySignalsMatchAndRejectExtraBytes(){
        String token=java.util.UUID.randomUUID().toString();
        var incoming=new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(StoryCodec.dialogueSession("ch1-3","",token)),RegistryAccess.EMPTY);
        try{assertEquals(new CutscenePackets.DialogueSessionS2C("ch1-3","",token),CutscenePackets.DialogueSessionS2C.CODEC.decode(incoming));assertEquals(0,incoming.readableBytes());}finally{incoming.release();}
        for(int action:List.of(0,1,2)){
            var buffer=buffer();String ending=action==1?"s20":"";
            CutscenePackets.DialogueSessionSignalC2S.CODEC.encode(buffer,new CutscenePackets.DialogueSessionSignalC2S(action,"ch1-3",token,ending));
            byte[] raw=bytes(buffer);assertEquals(new StoryCodec.SessionSignal(action,"ch1-3",token,ending),StoryCodec.sessionSignal(raw));
            assertThrows(IllegalArgumentException.class,()->StoryCodec.sessionSignal(java.util.Arrays.copyOf(raw,raw.length+1)));
            assertThrows(IllegalArgumentException.class,()->StoryCodec.sessionSignal(java.util.Arrays.copyOf(raw,raw.length-1)));
        }
        var invalid=buffer();CutscenePackets.DialogueSessionSignalC2S.CODEC.encode(invalid,new CutscenePackets.DialogueSessionSignalC2S(1,"ch1-3",token,"op somebody"));
        assertThrows(IllegalArgumentException.class,()->StoryCodec.sessionSignal(bytes(invalid)));
        assertEquals(StoryCodec.DIALOGUE_SESSION,CutscenePackets.DialogueSessionS2C.TYPE.id().toString());
        assertEquals(StoryCodec.DIALOGUE_SESSION_SIGNAL,CutscenePackets.DialogueSessionSignalC2S.TYPE.id().toString());
    }
    @Test void combinedReloadSuccessAndFailureMatchAcrossWire(){
        String token=java.util.UUID.randomUUID().toString();var incoming=new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(StoryCodec.reloadRequest(token)),RegistryAccess.EMPTY);
        try{assertEquals(token,CutscenePackets.ReloadRequestS2C.CODEC.decode(incoming).token());assertEquals(0,incoming.readableBytes());}finally{incoming.release();}
        for(int failures:List.of(0,1,2)){var out=buffer();CutscenePackets.ReloadResultC2S.CODEC.encode(out,new CutscenePackets.ReloadResultC2S(token,failures));assertEquals(new StoryCodec.ReloadResult(token,failures),StoryCodec.reloadResult(bytes(out)));}
        var bad=buffer();CutscenePackets.ReloadResultC2S.CODEC.encode(bad,new CutscenePackets.ReloadResultC2S(token,3));assertThrows(IllegalArgumentException.class,()->StoryCodec.reloadResult(bytes(bad)));
    }
}
