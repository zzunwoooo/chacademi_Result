package school.magiccodex.client;
import java.util.*;import java.util.function.Function;
import net.fabricmc.fabric.api.client.networking.v1.*;import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.MinecraftClient;import net.minecraft.network.RegistryByteBuf;import net.minecraft.network.codec.PacketCodec;import net.minecraft.network.packet.CustomPayload;import net.minecraft.util.Identifier;
import school.magiccodex.protocol.JournalProtocol;import school.magiccodex.protocol.JournalProtocol.*;
import school.magiccodex.client.api.ExternalJournal;
public final class JournalClient {
 private static final Map<String,String> chapters=new HashMap<>(); public static String chapter(String key){return chapters.getOrDefault(key,"");}
 private static List<Entry> server=List.of(),localRows=List.of();private static long publishAt;private static final Map<String,Entry> incoming=new LinkedHashMap<>();private static String token="";private static int nextPart;private static long requested;
 private interface Bytes{byte[] bytes();}
 public record Query(byte[] bytes)implements CustomPayload,Bytes{static final Id<Query>ID=new Id<>(Identifier.of(JournalProtocol.QUERY));static final PacketCodec<RegistryByteBuf,Query>CODEC=codec(Query::new);public Id<Query>getId(){return ID;}}
 public record Reply(byte[] bytes)implements CustomPayload,Bytes{static final Id<Reply>ID=new Id<>(Identifier.of(JournalProtocol.REPLY));static final PacketCodec<RegistryByteBuf,Reply>CODEC=codec(Reply::new);public Id<Reply>getId(){return ID;}}
 private static<T extends Bytes>PacketCodec<RegistryByteBuf,T>codec(Function<byte[],T>f){return new PacketCodec<>(){public T decode(RegistryByteBuf b){int n=b.readableBytes();if(n<4||n>JournalProtocol.BYTES){b.skipBytes(n);return f.apply(new byte[0]);}byte[] bytes=new byte[n];b.readBytes(bytes);return f.apply(bytes);}public void encode(RegistryByteBuf b,T p){b.writeBytes(p.bytes());}};}
 public static void initialize(){PayloadTypeRegistry.playC2S().register(Query.ID,Query.CODEC);PayloadTypeRegistry.playS2C().register(Reply.ID,Reply.CODEC);ClientPlayNetworking.registerGlobalReceiver(Reply.ID,(p,c)->{try{var m=JournalProtocol.decode(p.bytes());c.client().execute(()->receive(m));}catch(IllegalArgumentException ignored){}});ClientPlayConnectionEvents.JOIN.register((h,s,c)->reset());ClientPlayConnectionEvents.DISCONNECT.register((h,c)->reset());SharedNotifications.initialize();net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(c->{long now=System.currentTimeMillis();if(c.player!=null&&c.world!=null&&now>=publishAt&&ClientPlayNetworking.canSend(Query.ID)){publishAt=now+15000;publish();}});}
 private static void reset(){server=List.of();localRows=List.of();chapters.clear();publishAt=0;incoming.clear();token="";requested=0;nextPart=0;}
 private static void send(Message m){if(ClientPlayNetworking.canSend(Query.ID))ClientPlayNetworking.send(new Query(JournalProtocol.encode(m)));}
 public static void refresh(){long now=System.currentTimeMillis();if(now-requested<1000)return;requested=now;token=UUID.randomUUID().toString();incoming.clear();nextPart=0;send(new Message(0,token,"","","",0,0,List.of()));publish();}
 private static List<Entry> local(){chapters.clear();var out=new ArrayList<Entry>();try{for(var m:ExternalJournal.records()){if(out.size()>=JournalProtocol.MAX)break;try{chapters.put("story:"+m.get("id"),JournalProtocol.cut(m.getOrDefault("chapter",""),140));out.add(new Entry("story",m.get("id"),JournalProtocol.cut(m.get("title"),140),JournalProtocol.cut(m.get("description"),2000),m.getOrDefault("state","available"),!"journal".equals(m.get("category")),"",""));}catch(IllegalArgumentException ignored){}}}catch(RuntimeException ignored){}return List.copyOf(out);}
 /** 2026-10-25: 서버가 보낸 기록만 (메인 스토리도 서버 단계 기록). 런처 대화 기록(localRows)은 더 이상 섞지 않는다. */
 public static List<Entry> rows(){var map=new LinkedHashMap<String,Entry>();for(var e:server)map.put(e.key(),e);return List.copyOf(map.values());}
 private static void publish(){localRows=local();var rows=localRows;String id=UUID.randomUUID().toString();for(int i=0;i<Math.max(1,rows.size());i+=JournalProtocol.CHUNK)send(new Message(5,id,"","","",i/JournalProtocol.CHUNK,rows.size(),rows.subList(Math.min(i,rows.size()),Math.min(i+JournalProtocol.CHUNK,rows.size()))));}
 private static void receive(Message m){
  if(m.kind()==1&&m.token().equals(token)){if(m.part()!=nextPart||incoming.size()+m.entries().size()>JournalProtocol.MAX)return;nextPart++;for(var e:m.entries())incoming.put(e.key(),e);if(incoming.size()==m.total())server=List.copyOf(incoming.values());}
  else if(m.kind()==2){String result;try{result=ExternalJournal.edit(m.id(),m.operation());if(m.operation().equals("grant")&&!result.startsWith("ERROR"))SharedNotifications.offer("story:"+m.id(),"quest",local().stream().filter(e->e.id().equals(m.id())).map(Entry::title).findFirst().orElse(m.id()),"");}catch(RuntimeException e){result="ERROR: Story record edit failed";}send(new Message(3,m.token(),m.operation(),m.id(),JournalProtocol.cut(result,500),0,0,List.of()));publish();}
  else if(m.kind()==6){MinecraftClient.getInstance().setScreen(new QuestScreen(2));refresh();}
  else if(m.kind()==4)SharedNotifications.offer(m.id(),m.operation(),m.text(),m.entries().isEmpty()?"":m.entries().getFirst().description());
 }
}
