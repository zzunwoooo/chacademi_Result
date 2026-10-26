package kr.chacademy.cutscene;
import org.junit.jupiter.api.Test;
import io.netty.buffer.Unpooled;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.core.RegistryAccess;
import kr.chacademy.cutscene.net.CutscenePackets;
import kr.chacademy.storyplugin.StoryCodec;
import kr.chacademy.story.dialogue.ServerDialogues;
import static org.junit.jupiter.api.Assertions.*;
class DialogueContentProtocolTest {
 @Test void serverBytesDecodeToExactUnicodeDialogue(){String text="title: 한글\nscenes: {s1: {lines: [{text: '안녕 🌙'}]}}\n";var b=new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(StoryCodec.dialogueContent("ch1-3",text)),RegistryAccess.EMPTY);try{var value=CutscenePackets.DialogueContentS2C.CODEC.decode(b);assertEquals("ch1-3",value.id());assertEquals(text,value.text());assertEquals(0,b.readableBytes());}finally{b.release();}assertEquals(StoryCodec.DIALOGUE_CONTENT,CutscenePackets.DialogueContentS2C.TYPE.id().toString());assertEquals(StoryCodec.DIALOGUE_CONTENT_MAX_CHARS,ServerDialogues.MAX_CHARS);}
 @Test void utf8MaximumFitsBelowOneMiBAndRoundTrips(){String text="가".repeat(ServerDialogues.MAX_CHARS);byte[] data=StoryCodec.dialogueContent("ch1-3",text);assertTrue(data.length<1_000_000);var b=new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(data),RegistryAccess.EMPTY);try{String received=CutscenePackets.DialogueContentS2C.CODEC.decode(b).text();assertEquals(text.length(),received.length());assertTrue(text.equals(received),"maximum content preserved exactly");}finally{b.release();}}
 @Test void serverAndClientRejectOverlongPayload(){String oversized="x".repeat(ServerDialogues.MAX_CHARS+1);assertThrows(IllegalArgumentException.class,()->StoryCodec.dialogueContent("ch1-3",oversized));var b=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);try{b.writeUtf("ch1-3",64);byte[] bytes=oversized.getBytes(java.nio.charset.StandardCharsets.UTF_8);b.writeVarInt(bytes.length);b.writeBytes(bytes);assertThrows(RuntimeException.class,()->CutscenePackets.DialogueContentS2C.CODEC.decode(b));}finally{b.release();}}
}
