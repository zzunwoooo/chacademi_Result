package kr.chacademy.storyplugin;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class CutsceneFailureQueueTest {
 @Test void missingTestDoesNotBlockDirectTutorialAndPreservesAllRecords(){
  var ps=new StoryState.PlayerStory();
  var failed=new StoryState.Request(StoryState.Kind.CUTSCENE,"test",1);failed.sent=true;ps.active=failed;
  var next=new StoryState.Request(StoryState.Kind.CUTSCENE,"magic_tutorial",2);ps.queue.add(next);
  var done=new StoryState.Done();done.times=1;ps.done.put("ch1-3",done);ps.fired.put("ch1-3",new TreeSet<>(Set.of("reward")));ps.gained.put("teacher",5);
  var records=ps.toMap();assertTrue(ps.cancelFailedCutscene("test"));assertSame(next,ps.activateNext());
  assertFalse(ps.has(StoryState.Kind.CUTSCENE,"test"));assertTrue(ps.effects.isEmpty());
  for(String key:List.of("done","fired","gained"))assertEquals(records.get(key),ps.toMap().get(key));
  assertFalse(ps.cancelFailedCutscene("test"));assertSame(next,ps.active);
 }
 @Test void failureCannotCancelDifferentUnsentOrDialogueRequest(){
  var ps=new StoryState.PlayerStory();var r=new StoryState.Request(StoryState.Kind.CUTSCENE,"magic_tutorial",1);ps.active=r;
  assertFalse(ps.cancelFailedCutscene(r.id));r.sent=true;assertFalse(ps.cancelFailedCutscene("test"));assertSame(r,ps.active);
  var d=new StoryState.Request(StoryState.Kind.DIALOGUE,"ch1-3",2);d.sent=true;ps.active=d;assertFalse(ps.cancelFailedCutscene(d.id));assertSame(d,ps.active);
 }
 @Test void normalPlayingCutsceneStillQueuesDirectRequests(){
  var ps=new StoryState.PlayerStory();var current=new StoryState.Request(StoryState.Kind.CUTSCENE,"intro",1);current.sent=true;ps.active=current;
  ps.queue.add(new StoryState.Request(StoryState.Kind.CUTSCENE,"magic_tutorial",2));assertSame(current,ps.activateNext());assertEquals(1,ps.queue.size());
 }
 @Test void stopRecoversPreviouslyParkedRequestWithoutClearingQueue(){
  var ps=new StoryState.PlayerStory();var old=new StoryState.Request(StoryState.Kind.CUTSCENE,"test",1);old.parked=true;ps.active=old;
  var tutorial=new StoryState.Request(StoryState.Kind.CUTSCENE,"magic_tutorial",2);ps.queue.add(tutorial);
  assertSame(old,ps.activateNext());assertTrue(ps.cancelCutscene(old));assertSame(tutorial,ps.activateNext());assertTrue(ps.effects.isEmpty());
  assertFalse(ps.cancelCutscene(old));assertSame(tutorial,ps.active);
 }
 @Test void disconnectResetsTransientFlagsAndPreservesLegitimateResume(){
  var ps=new StoryState.PlayerStory();var active=new StoryState.Request(StoryState.Kind.CUTSCENE,"magic_tutorial",1);active.sent=true;active.parked=true;active.resends=6;ps.active=active;
  active.resetTransient();assertFalse(active.sent);assertFalse(active.parked);assertEquals(0,active.resends);assertSame(active,ps.activateNext());
 }
}
