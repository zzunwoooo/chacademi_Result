package kr.chacademy.storyplugin;
import java.util.*;
import java.util.function.*;
import java.nio.file.Path;
import io.netty.buffer.Unpooled;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.core.RegistryAccess;
import kr.chacademy.cutscene.net.CutscenePackets;
import kr.chacademy.story.compat.MagicCodexClientLink;
import kr.chacademy.story.dialogue.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
/** Real Runner -> Presenter -> session codecs -> server queue -> cutscene codec.
 * Only the graphics surface and Bukkit console dispatch boundary are fake. */
class DialogueCutsceneTransitionTest {
 @TempDir Path temp;
 public static class Surface {
  static boolean showing;static BiConsumer<String,String> choice;static String session;
  public static int apiVersion(){return 1;}
  public static boolean show(Map<String,Object> frame,BiConsumer<String,String> c,Consumer<String> closed){showing=true;choice=c;session=(String)frame.get("session");return true;}
  public static void close(String id){if(Objects.equals(session,id))showing=false;}
  public static boolean isShowing(String id){return showing&&Objects.equals(session,id);}
 }
 static RegistryFriendlyByteBuf buf(){return new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);}
 static byte[] bytes(RegistryFriendlyByteBuf b){byte[] data=new byte[b.readableBytes()];b.readBytes(data);b.release();return data;}
 static StoryCodec.SessionSignal signal(int action,String id,String token,String ending){var b=buf();CutscenePackets.DialogueSessionSignalC2S.CODEC.encode(b,new CutscenePackets.DialogueSessionSignalC2S(action,id,token,ending));return StoryCodec.sessionSignal(bytes(b));}
 static String receivedCutscene(StoryState.Request request){var b=new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(StoryCodec.cutscenePlay(request.id,request.mode)),RegistryAccess.EMPTY);try{return CutscenePackets.PlayS2C.CODEC.decode(b).id();}finally{b.release();}}
 @Test void missingIdThenDirectValidIdStartsWithoutStopAndRejectsStaleFailure(){
  var ps=new StoryState.PlayerStory();var missing=new StoryState.Request(StoryState.Kind.CUTSCENE,"missing_id",1);ps.queue.add(missing);assertSame(missing,ps.activateNext());missing.sent=true;
  assertEquals("missing_id",receivedCutscene(missing));
  var b=buf();CutscenePackets.StoryFailC2S.CODEC.encode(b,new CutscenePackets.StoryFailC2S(0,"missing_id","missing"));var failure=StoryCodec.storyFail(bytes(b));
  assertTrue(ps.cancelFailedCutscene(failure.id()));assertNull(ps.active);assertTrue(ps.effects.isEmpty());
  var valid=new StoryState.Request(StoryState.Kind.CUTSCENE,"magic_tutorial",2);ps.queue.add(valid);assertSame(valid,ps.activateNext());valid.sent=true;
  assertEquals("magic_tutorial",receivedCutscene(valid));assertFalse(ps.cancelFailedCutscene(failure.id()));assertSame(valid,ps.active);
 }
 @Test void completedDialogueDispatchesTwiceAfterMissingCutsceneAndWaitsForScreenClose() throws Exception {
  MagicCodexClientLink.nickname("x");var resolve=MagicCodexClientLink.class.getDeclaredMethod("resolveDialogue",String.class);resolve.setAccessible(true);resolve.invoke(null,Surface.class.getName());
  var ps=new StoryState.PlayerStory();var failed=new StoryState.Request(StoryState.Kind.CUTSCENE,"test",1);failed.sent=true;ps.active=failed;
  assertTrue(ps.cancelFailedCutscene("test"));
  var registry=new ServerCommandRegistry();registry.replace(Map.of("commands",Map.of("ch1-3",List.of("cutscene play {player} magic_tutorial"))));
  var sessions=new LocalEndingSessions();UUID player=UUID.randomUUID();var ledger=new LocalDialogueStore(temp.resolve("progress.properties"));int[] counts={0,0};
  var d=DialogueParser.parse("ch1-3",Map.of("scenes",Map.of("s20",Map.of("lines",List.of(Map.of("text","Finish"))))));
  try {for(int n=0;n<2;n++){
   var session=sessions.begin(player,"ch1-3",registry.commands("ch1-3"),0);var ready=signal(0,session.id(),session.token(),"");assertTrue(sessions.ready(player,ready.id(),ready.token(),0));
   var runner=new DialogueRunner(d,null,Map.of(),Map.of(),new DialogueRunner.Listener(){
    public void event(String id,String event,String npc,int amount){}
    public void done(String id,String ending){
     assertTrue(Surface.showing,"Runner completion precedes Presenter screen close; cutscene must wait");
     var done=signal(1,id,session.token(),ending);
     for(String command:sessions.finish(player,done.id(),done.token(),done.ending(),0)){
      assertEquals("cutscene play Zzunwoo magic_tutorial",command.replace("{player}","Zzunwoo"));counts[0]++;
      ps.queue.add(new StoryState.Request(StoryState.Kind.CUTSCENE,"magic_tutorial",ps.nextSeq()));
     }
     assertTrue(sessions.finish(player,done.id(),done.token(),done.ending(),0).isEmpty());
     try{if(ledger.complete(id))counts[1]++;}catch(Exception e){throw new RuntimeException(e);}
    }
   });
   assertTrue(DialoguePresenter.start(runner,false));Surface.choice.accept(Surface.session,"");
   assertTrue(runner.isEnded());assertFalse(DialoguePresenter.isActive());assertFalse(Surface.showing,"Presenter closes the dialogue before deferred cutscene opens");
   var cutscene=ps.activateNext();assertNotNull(cutscene);assertEquals("magic_tutorial",receivedCutscene(cutscene));assertTrue(ps.cancelCutscene(cutscene));
  }}finally{DialoguePresenter.stop();resolve.invoke(null,"school.magiccodex.client.api.ExternalDialogue");}
  assertArrayEquals(new int[]{2,1},counts);assertTrue(ledger.done("ch1-3"));
 }
}
