package kr.chacademy.story.dialogue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import io.netty.buffer.Unpooled;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.core.RegistryAccess;
import kr.chacademy.cutscene.net.CutscenePackets;
import kr.chacademy.storyplugin.StoryCodec;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class StoryContentPathsTest {
 @Test @EnabledIfEnvironmentVariable(named="CHACA_STORY_CONTENT_ROOT",matches=".+")
 void everyChapterDialogueLoadsFromServerAndAllPortraitsResolveInNewPack()throws Exception{
  Path root=Path.of(System.getenv("CHACA_STORY_CONTENT_ROOT"));Path dialogues=root.resolve("story/ch1/chaca_dialogue"),textures=root.resolve("resourcepacks/chacademia_npc/assets/magiccodex/textures/gui/dialogue");int count=0;Set<String> pictures=new HashSet<>();
  try(var dirs=Files.list(dialogues)){
   for(var folder:dirs.filter(Files::isDirectory).sorted().toList()){
    Path file=folder.resolve("dialogue.yml");if(!Files.isRegularFile(file))continue;String id=folder.getFileName().toString(),text=Files.readString(file);var dialogue=DialogueLoader.load(dialogues,id);assertEquals(id,dialogue.id());
    var b=new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(StoryCodec.dialogueContent(id,text)),RegistryAccess.EMPTY);try{var received=CutscenePackets.DialogueContentS2C.CODEC.decode(b);assertEquals(text.length(),received.text().length(),id);assertTrue(text.equals(received.text()),id);assertEquals(0,b.readableBytes());}finally{b.release();}
    for(var speaker:dialogue.speakers().values())for(String image:speaker.portraits().values()){pictures.add(image);assertTrue(Files.isRegularFile(textures.resolve(image)),id+" -> "+image);}
    count++;
   }
  }
  assertEquals(33,count);assertEquals(20,pictures.size());
 }
}
