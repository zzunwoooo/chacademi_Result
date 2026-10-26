package school.magiccodex.client;
import java.util.function.Function;import net.fabricmc.fabric.api.client.networking.v1.*;import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;import net.minecraft.network.RegistryByteBuf;import net.minecraft.network.codec.PacketCodec;import net.minecraft.network.packet.CustomPayload;import net.minecraft.util.Identifier;import net.minecraft.util.Util;import school.magiccodex.protocol.GeneralPresetProtocol;import school.magiccodex.protocol.GeneralPresetModeProtocol;
final class GeneralPresetClient {
 private static long sequence,pending,until,transition;private static boolean joined,pendingMode;private static Runnable after;
 private interface Bytes{byte[] bytes();}
 record Request(byte[] bytes)implements CustomPayload,Bytes{static final Id<Request>ID=new Id<>(Identifier.of(GeneralPresetProtocol.REQUEST));public Id<Request>getId(){return ID;}}
 record Response(byte[] bytes)implements CustomPayload,Bytes{static final Id<Response>ID=new Id<>(Identifier.of(GeneralPresetProtocol.RESPONSE));public Id<Response>getId(){return ID;}}
 record ModeRequest(byte[] bytes)implements CustomPayload,Bytes{static final Id<ModeRequest>ID=new Id<>(Identifier.of(GeneralPresetModeProtocol.REQUEST));public Id<ModeRequest>getId(){return ID;}}
 record ModeResponse(byte[] bytes)implements CustomPayload,Bytes{static final Id<ModeResponse>ID=new Id<>(Identifier.of(GeneralPresetModeProtocol.RESPONSE));public Id<ModeResponse>getId(){return ID;}}
 private static <T extends Bytes>PacketCodec<RegistryByteBuf,T>codec(Function<byte[],T> factory,int size){return new PacketCodec<>(){public T decode(RegistryByteBuf b){int n=b.readableBytes();if(n!=size){b.skipBytes(n);return factory.apply(new byte[0]);}byte[] x=new byte[n];b.readBytes(x);return factory.apply(x);}public void encode(RegistryByteBuf b,T p){if(p.bytes().length!=size)throw new IllegalArgumentException();b.writeBytes(p.bytes());}};}
 static void initialize(){
  PayloadTypeRegistry.playC2S().register(Request.ID,codec(Request::new,10));PayloadTypeRegistry.playS2C().register(Response.ID,codec(Response::new,11));
  PayloadTypeRegistry.playC2S().register(ModeRequest.ID,codec(ModeRequest::new,10));PayloadTypeRegistry.playS2C().register(ModeResponse.ID,codec(ModeResponse::new,11));
  ClientPlayNetworking.registerGlobalReceiver(Response.ID,(payload,ctx)->{try{var r=GeneralPresetProtocol.response(payload.bytes());ctx.client().execute(()->receive(r.sequence(),r.selected(),r.status(),false));}catch(IllegalArgumentException ignored){}});
  ClientPlayNetworking.registerGlobalReceiver(ModeResponse.ID,(payload,ctx)->{try{var r=GeneralPresetModeProtocol.response(payload.bytes());ctx.client().execute(()->receive(r.sequence(),r.selected(),r.status(),true));}catch(IllegalArgumentException ignored){}});
  ClientPlayConnectionEvents.JOIN.register((h,s,c)->reset());ClientPlayConnectionEvents.DISCONNECT.register((h,c)->reset());
 }
 private static void reset(){sequence=pending=until=0;joined=false;pendingMode=false;after=null;CastingClient.resetPresetSession();}
 static boolean pending(){return pending!=0;}
 static void tick(){long now=Util.getMeasuringTimeMs();if(pending!=0&&now>=until){pending=0;after=null;notice("프리셋 응답을 받지 못했습니다. 다시 시도해 주세요.");}if(!joined&&(ClientPlayNetworking.canSend(ModeRequest.ID)||ClientPlayNetworking.canSend(Request.ID))){joined=true;int preferred=CastingClient.preferredPreset();if(preferred==NormalPresetMode.OFF&&!ClientPlayNetworking.canSend(ModeRequest.ID)){notice("이 서버는 OFF 전환 업데이트가 필요합니다. 일반 프리셋 1을 사용합니다.");preferred=1;}request(preferred,null);}}
 static void request(int index,Runnable callback){
  if(pending!=0)return;if(CastingClient.matchLoadout()){notice("전투 중에는 일반 프리셋을 변경할 수 없습니다.");return;}
  boolean mode=ClientPlayNetworking.canSend(ModeRequest.ID);if(!mode&&index==NormalPresetMode.OFF){notice("OFF 전환은 서버 프리셋 업데이트 후 사용할 수 있습니다.");return;}
  if(!mode&&!ClientPlayNetworking.canSend(Request.ID)){notice("서버의 프리셋 기능을 확인할 수 없습니다.");return;}
  try{CastingClient.validatePreset(1);CastingClient.validatePreset(2);}catch(Exception e){notice("프리셋 설정을 읽지 못했습니다. 기존 프리셋을 유지합니다.");return;}
  pending=++sequence;until=Util.getMeasuringTimeMs()+4000;after=callback;pendingMode=mode;
  if(mode)ClientPlayNetworking.send(new ModeRequest(GeneralPresetModeProtocol.encode(new GeneralPresetModeProtocol.Request(pending,index))));else ClientPlayNetworking.send(new Request(GeneralPresetProtocol.encode(new GeneralPresetProtocol.Request(pending,index))));
 }
 private static void receive(long seq,int selected,int status,boolean mode){
  if(seq!=pending||mode!=pendingMode)return;pending=0;var callback=after;after=null;
  try{CastingClient.confirmPreset(selected);}catch(Exception e){notice("프리셋 설정을 읽거나 저장하지 못했습니다.");return;}
  if(status!=GeneralPresetProtocol.OK){notice(switch(status){case GeneralPresetProtocol.COOLDOWN->"마법 쿨타임이 남아 있어 프리셋을 변경할 수 없습니다.";case GeneralPresetProtocol.COMBAT->"전투 중에는 일반 프리셋을 변경할 수 없습니다.";default->"지금은 프리셋을 변경할 수 없습니다.";});return;}
  transition=Util.getMeasuringTimeMs();if(callback!=null)callback.run();
 }
 static float lift(long now){float t=Math.clamp((now-transition)/180f,0,1);return 4*(1-t)*(1-t);}
 private static void notice(String value){SharedNotifications.offer("magic:preset","warning","마법 프리셋",value);}
}
