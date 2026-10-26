package kr.chacademy.storyplugin;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class LocalEndingSessionsTest {
 final UUID player=UUID.randomUUID();
 final StoryDef def=StoryDef.parse("ch1-3",Map.of("endings",Map.of("s20",List.of("cutscene play {player} magic_tutorial"),"s21",List.of("cutscene play {player} magic_tutorial"))),null,new ArrayList<>());
 @Test void twoAuthorizedCompletionsEachExecuteOnceWithoutPlayerAdminPermission(){
  var sessions=new LocalEndingSessions();int commands=0;
  for(int i=0;i<2;i++){var s=sessions.begin(player,def.id,def.endings.get("s20"),i);assertNotNull(s);assertEquals(s,sessions.begin(player,def.id,def.endings.get("s20"),i));assertTrue(sessions.ready(player,s.id(),s.token(),i));assertTrue(sessions.ready(player,s.id(),s.token(),i));commands+=sessions.finish(player,s.id(),s.token(),"s20",i).size();assertTrue(sessions.finish(player,s.id(),s.token(),"s20",i).isEmpty());}assertEquals(2,commands);
 }
 @Test void unregisteredIdWrongUserWrongTokenWrongEndingAndBeforeReadyCannotDispatch(){
  var sessions=new LocalEndingSessions();assertNull(sessions.begin(player,"ch1-3",null,0));var s=sessions.begin(player,def.id,def.endings.get("s20"),0);
  assertTrue(sessions.finish(player,s.id(),s.token(),"s20",1).isEmpty());
  assertFalse(sessions.ready(UUID.randomUUID(),s.id(),s.token(),1));assertTrue(sessions.ready(player,s.id(),s.token(),1));
  assertTrue(sessions.finish(UUID.randomUUID(),s.id(),s.token(),"s20",1).isEmpty());assertTrue(sessions.finish(player,"unknown",s.token(),"s20",1).isEmpty());assertTrue(sessions.finish(player,s.id(),UUID.randomUUID().toString(),"s20",1).isEmpty());assertTrue(sessions.finish(player,s.id(),s.token(),"invalid ending",1).isEmpty());assertEquals(1,sessions.finish(player,s.id(),s.token(),"s20",1).size());
 }
 @Test void abortDisconnectReloadAndExpirationNeverRunCommands(){
  for(int mode=0;mode<4;mode++){var sessions=new LocalEndingSessions();var s=sessions.begin(player,def.id,def.endings.get("s20"),0);sessions.ready(player,s.id(),s.token(),0);if(mode==0)sessions.abort(player,s.id(),s.token(),0);if(mode==1)sessions.cancel(player);if(mode==2)sessions.clear();assertTrue(sessions.finish(player,s.id(),s.token(),"s20",mode==3?LocalEndingSessions.TTL:1).isEmpty());}
 }
 @Test void oldCompletionCannotConsumeNewPlayback(){var sessions=new LocalEndingSessions();var a=sessions.begin(player,def.id,def.endings.get("s20"),0);sessions.ready(player,a.id(),a.token(),0);sessions.finish(player,a.id(),a.token(),"s20",0);var b=sessions.begin(player,def.id,def.endings.get("s20"),1);sessions.ready(player,b.id(),b.token(),1);assertNotEquals(a.token(),b.token());assertFalse(sessions.abort(player,a.id(),a.token(),1));assertTrue(sessions.finish(player,a.id(),a.token(),"s20",1).isEmpty());assertEquals(1,sessions.finish(player,b.id(),b.token(),"s21",1).size());}
 @Test void sortingEndingIsValidatedBeforeLiteralPlaceholderSubstitution(){
  var sessions=new LocalEndingSessions();var s=sessions.begin(player,"ch1-14",List.of("기숙사분류 결정 {player} {ending}"),0);sessions.ready(player,s.id(),s.token(),0);
  for(String invalid:new String[]{null,"p_33 evil","p_33\nstop","$1","{player}","p_33;stop"})assertTrue(sessions.finish(player,s.id(),s.token(),invalid,1).isEmpty());
  var commands=sessions.finish(player,s.id(),s.token(),"p_33",1);assertEquals(List.of("기숙사분류 결정 {player} p_33"),commands.stream().map(c->c.replace("{ending}","p_33")).toList());
  assertTrue(sessions.finish(player,s.id(),s.token(),"p_33",1).isEmpty());
 }
}
