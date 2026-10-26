package school.magiccodex.party;
import com.velocitypowered.api.proxy.*;
import com.velocitypowered.api.proxy.server.ServerInfo;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.permission.Tristate;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import java.lang.reflect.*;
import java.net.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class NetworkChatIntegrationTest {
 @TempDir Path dir;
 final Map<UUID,Person> people=new LinkedHashMap<>();PartyPlugin plugin;
 class Person {
  final UUID id=UUID.randomUUID();final List<Component> received=new ArrayList<>();final Player player;ServerConnection connection;boolean muted;final Set<String> denied=new HashSet<>();Runnable afterReceive=()->{};
  Person(String server){
   player=mock(Player.class,(method,args)->switch(method){case "getUniqueId"->id;case "getUsername"->"Account";case "getCurrentServer"->Optional.ofNullable(connection);case "getPermissionValue"->muted&&args[0].equals("chacademi.chat.muted")?Tristate.TRUE:denied.contains(args[0])?Tristate.FALSE:Tristate.UNDEFINED;case "sendMessage"->{received.add((Component)args[0]);afterReceive.run();yield null;}default->null;});
   move(server);people.put(id,this);
  }
  void move(String server){connection=mock(ServerConnection.class,(method,args)->switch(method){case "getServerInfo"->new ServerInfo(server,new InetSocketAddress("localhost",25566));case "getPlayer"->player;default->null;});}
 }
 interface Calls {Object call(String method,Object[] args);}
 @SuppressWarnings("unchecked") static <T>T mock(Class<T> type,Calls calls){return (T)java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(proxy,method,args)->{if(method.getName().equals("equals"))return proxy==args[0];if(method.getName().equals("hashCode"))return System.identityHashCode(proxy);if(method.getName().equals("toString"))return type.getSimpleName();return calls.call(method.getName(),args==null?new Object[0]:args);});}
 @BeforeEach void setup()throws Exception {
  var proxy=mock(ProxyServer.class,(method,args)->switch(method){case "getPlayer"->{Person p=people.get(args[0]);yield Optional.ofNullable(p==null?null:p.player);}case "getAllPlayers"->people.values().stream().map(p->p.player).toList();default->null;});
  plugin=new PartyPlugin(proxy,LoggerFactory.getLogger(getClass()),dir);field("network").set(plugin,new NetworkChat(Set.of("school","wild","arena")));((Set<String>)field("trusted").get(plugin)).addAll(Set.of("school","wild","arena"));
 }
 static Field field(String name)throws Exception {var f=PartyPlugin.class.getDeclaredField(name);f.setAccessible(true);return f;}
 void drain()throws Exception {((ExecutorService)field("worker").get(plugin)).submit(()->{}).get(3,TimeUnit.SECONDS);}
 void identity(Person p,int house)throws Exception {plugin.identity(new PluginMessageEvent(p.connection,p.player,MinecraftChannelIdentifier.from("chacademi:party_identity"),NetworkChatTest.wire(2,"별빛",house)));drain();}
 void send(Person p,String channel,String text)throws Throwable {
  var method=PartyPlugin.class.getDeclaredMethod("networkMessage",Player.class,String.class,String.class);method.setAccessible(true);
  String name=switch(channel){case "g"->"전체";case "h"->"기숙사";default->channel;};
  try{method.invoke(plugin,p.player,name,text);}catch(InvocationTargetException e){throw e.getCause();}
 }
 static String plain(Component c){String own=c instanceof TextComponent t?t.content():"";for(Component child:c.children())own+=plain(child);return own;}
 @AfterEach void close(){plugin.shutdown(new ProxyShutdownEvent());}
 @Test void actualHandlerRejectsClientAndStaleBackendPackets()throws Exception {
  var a=new Person("school");var channel=MinecraftChannelIdentifier.from("chacademi:party_identity");
  var forged=new PluginMessageEvent(a.player,a.connection,channel,NetworkChatTest.wire(2,"위조",3));plugin.identity(forged);drain();assertEquals(PluginMessageEvent.ForwardResult.handled(),forged.getResult());
  var network=(NetworkChat)field("network").get(plugin);assertNull(network.identity(a.id,a.connection,System.currentTimeMillis()));
  var old=a.connection;a.move("wild");plugin.identity(new PluginMessageEvent(old,a.player,channel,NetworkChatTest.wire(2,"낡음",0)));drain();assertNull(network.identity(a.id,a.connection,System.currentTimeMillis()));identity(a,1);assertEquals(1,network.identity(a.id,a.connection,System.currentTimeMillis()).house());
 }
 @Test void commandDeliversExactlyOnceWithLiteralTextAndNoChatEventRewrite()throws Throwable {
  var a=new Person("school");var b=new Person("wild");identity(a,0);identity(b,1);send(a,"g","<red>[기숙사] test");assertEquals(1,a.received.size());assertEquals(1,b.received.size());
  String text=plain(b.received.getFirst());assertTrue(text.startsWith("[전체] \uE114 \uE110 별빛 : "));assertTrue(text.endsWith("<red>[기숙사] test"));assertNull(b.received.getFirst().clickEvent());
  assertThrows(IllegalArgumentException.class,()->send(a,"g","duplicate"));assertEquals(1,b.received.size());
 }
 @Test void houseRecipientsAndMutePermissionAreEnforcedBeforeSend()throws Throwable {
  var a=new Person("school");var same=new Person("wild");var other=new Person("wild");identity(a,2);identity(same,2);identity(other,3);send(a,"h","house");assertEquals(1,same.received.size());assertTrue(other.received.isEmpty());assertTrue(plain(same.received.getFirst()).startsWith("[기숙사]"));
  other.muted=true;assertThrows(IllegalArgumentException.class,()->send(other,"g","muted"));assertEquals(1,a.received.size());
 }
 @Test void connectionOrIdentityChangeDuringFanoutDoesNotLeakToLaterRecipient()throws Throwable {
  var a=new Person("school");var b=new Person("wild");identity(a,0);identity(b,0);
  a.afterReceive=()->b.move("school");send(a,"h","moving");assertEquals(1,a.received.size());assertTrue(b.received.isEmpty());
 }
 @Test void explicitDenyBlocksNetworkAndUndefinedPreservesPartyDefaultAllow()throws Exception {
  var a=new Person("school");identity(a,0);a.denied.add("chacademi.chat.global");assertThrows(IllegalArgumentException.class,()->send(a,"g","denied"));assertTrue(a.received.isEmpty());
  // /파티 노드의 requires: 명시적 false 만 막는다.
  assertTrue(plugin.partyAllowed(a.player));a.denied.add("chacademi.party");assertFalse(plugin.partyAllowed(a.player));
 }

 @Test void arenaCanSendGlobalAndReceiveSameHouseWithoutWildServerLabel()throws Throwable {
  var a=new Person("arena");var school=new Person("school");var wild=new Person("wild");identity(a,2);identity(school,2);identity(wild,3);
  send(a,"g","arena-global");for(var p:List.of(a,school,wild))assertEquals(1,p.received.size());
  String text=plain(school.received.getFirst());assertTrue(text.startsWith("[전체] [아레나] "));assertFalse(text.contains("\uE115"));
  send(school,"h","school-house");assertEquals(2,a.received.size());assertEquals(2,school.received.size());assertEquals(1,wild.received.size());
  assertTrue(plain(a.received.getLast()).startsWith("[기숙사]"));
 }
}
