package school.magiccodex.party;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
class NetworkChatTest {
 static byte[] wire(int version,String name,int house)throws IOException {
  var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);out.writeByte(version);out.writeUTF(name);if(version==2)out.writeByte(house);return bytes.toByteArray();
 }
 @Test void globalUniqueAudienceAndFourHousePartitionsAcrossAllThreeBackends()throws Exception {
  var chat=new NetworkChat(Set.of("school","wild","arena","lobby"));var online=new LinkedHashMap<UUID,NetworkChat.Connection>();var houses=new HashMap<Integer,Set<UUID>>();
  for(int house=0;house<4;house++)for(String server:List.of("school","wild","arena")){
   var id=UUID.randomUUID();var source=new Object();online.put(id,new NetworkChat.Connection(source,server));chat.accept(id,source,source,server,true,wire(2,"별빛"+house,house),100);houses.computeIfAbsent(house,k->new HashSet<>()).add(id);
  }
  var lobby=UUID.randomUUID();online.put(lobby,new NetworkChat.Connection(new Object(),"lobby"));
  for(int house=0;house<4;house++){var actor=houses.get(house).iterator().next();assertEquals(houses.get(house),chat.recipients(NetworkChat.Channel.HOUSE,actor,online,101));assertEquals(12,chat.recipients(NetworkChat.Channel.GLOBAL,actor,online,101).size());}
 }
 @Test void missingV1ExpiredOfflineTransferAndHouseChangeFailClosed()throws Exception {
  var chat=new NetworkChat(Set.of("school","wild"));var a=UUID.randomUUID();var b=UUID.randomUUID();var sa=new Object();var sb=new Object();var online=new HashMap<UUID,NetworkChat.Connection>();online.put(a,new NetworkChat.Connection(sa,"school"));online.put(b,new NetworkChat.Connection(sb,"wild"));
  assertThrows(IllegalArgumentException.class,()->chat.recipients(NetworkChat.Channel.HOUSE,a,online,10));
  chat.accept(a,sa,sa,"school",true,wire(1,"이전",0),10);assertThrows(IllegalArgumentException.class,()->chat.recipients(NetworkChat.Channel.HOUSE,a,online,11));
  chat.accept(a,sa,sa,"school",true,wire(2,"별빛",1),10);chat.accept(b,sb,sb,"wild",true,wire(2,"달빛",1),10);assertEquals(Set.of(a,b),chat.recipients(NetworkChat.Channel.HOUSE,a,online,11));
  chat.accept(b,sb,sb,"wild",true,wire(2,"달빛",2),12);assertEquals(Set.of(a),chat.recipients(NetworkChat.Channel.HOUSE,a,online,13));
  chat.accept(b,sb,sb,"wild",true,wire(2,"달빛",1),14);online.put(b,new NetworkChat.Connection(new Object(),"school"));assertEquals(Set.of(a),chat.recipients(NetworkChat.Channel.HOUSE,a,online,15));
  online.remove(b);assertEquals(Set.of(a),chat.recipients(NetworkChat.Channel.HOUSE,a,online,15));
  assertThrows(IllegalArgumentException.class,()->chat.recipients(NetworkChat.Channel.HOUSE,a,online,10010));
  chat.accept(a,sa,sa,"school",true,wire(2,"별빛",-1),10011);assertThrows(IllegalArgumentException.class,()->chat.recipients(NetworkChat.Channel.HOUSE,a,online,10012));
  chat.disconnect(a);assertNull(chat.identity(a,sa,10012));
 }
 @Test void malformedAndSpoofedIdentityNeverReplaceTrustedSnapshot()throws Exception {
  var chat=new NetworkChat(Set.of("school","wild"));var id=UUID.randomUUID();var source=new Object();byte[] good=wire(2,"정상",0);assertTrue(chat.accept(id,source,source,"school",true,good,0));
  assertFalse(chat.accept(id,source,source,"school",false,wire(2,"위조",3),1));assertFalse(chat.accept(id,source,new Object(),"school",true,good,1));assertFalse(chat.accept(id,source,source,"other",true,good,1));
  for(byte[] bad:List.of(new byte[0],new byte[257],wire(3,"x",0),wire(2,"x",4),wire(2,"x",-2),wire(2,"\u00a7cx",0),Arrays.copyOf(good,good.length+1),Arrays.copyOf(good,good.length-1)))assertFalse(chat.accept(id,source,source,"school",true,bad,1));
  assertEquals("정상",chat.identity(id,source,2).nickname());assertEquals(0,chat.identity(id,source,2).house());
 }
 @Test void sharedRateLimitAndMessageValidationBoundAllRoutes(){
  var next=new HashMap<UUID,Long>();var id=UUID.randomUUID();NetworkChat.claimChat(next,id,1000);assertThrows(IllegalArgumentException.class,()->NetworkChat.claimChat(next,id,1699));NetworkChat.claimChat(next,id,1700);
  assertEquals("<red>[기숙사] literal",PartyBook.message("<red>[기숙사] literal"));assertEquals(240,PartyBook.message("x".repeat(240)).length());for(String bad:List.of("","x".repeat(241),"a\nb","\u00a7cfoo","a\u202Eb"))assertThrows(IllegalArgumentException.class,()->PartyBook.message(bad));
 }
 @Test void workerQueueIsBoundedAndRejectsOverflow()throws Exception {
  var worker=PartyPlugin.newWorker();var started=new CountDownLatch(1);var release=new CountDownLatch(1);
  try{worker.execute(()->{started.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}});assertTrue(started.await(3,TimeUnit.SECONDS));for(int i=0;i<256;i++)worker.execute(()->{});assertThrows(RejectedExecutionException.class,()->worker.execute(()->{}));}
  finally{release.countDown();worker.shutdownNow();assertTrue(worker.awaitTermination(3,TimeUnit.SECONDS));}
 }

 @Test void arenaStillRequiresTrustedBackendAndCannotBroadenToLobby()throws Exception {
  var chat=new NetworkChat(Set.of("school","wild","lobby"));assertFalse(chat.networkServer("arena"));assertFalse(chat.networkServer("lobby"));
  var id=UUID.randomUUID();var source=new Object();assertFalse(chat.accept(id,source,source,"arena",true,wire(2,"별빛",0),0));
  assertThrows(IllegalArgumentException.class,()->chat.sender(id,Map.of(id,new NetworkChat.Connection(source,"arena")),1));
 }
}
