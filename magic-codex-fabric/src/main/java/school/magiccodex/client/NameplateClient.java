package school.magiccodex.client;
import java.util.*;
import java.lang.reflect.Method;
import net.fabricmc.fabric.api.client.networking.v1.*;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.*;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.*;
import school.magiccodex.protocol.NameplateProtocol;
public final class NameplateClient {
 private static Map<Integer,NameplateProtocol.Entry> entries=Map.of();private static long received;private static boolean serverReady;
 private static HudTextureCache textures;private static CodexTypography text;private static boolean ready;
 private static Object world;private static Method height;private static boolean heightChecked;
 private static final String[] STYLES={"arkeon","lumina","bestiaz","noxer","professor","unassigned"};
 public record Snapshot(byte[] bytes) implements CustomPayload {
  public static final Id<Snapshot> ID=new Id<>(Identifier.of(NameplateProtocol.CHANNEL));
  public static final PacketCodec<RegistryByteBuf,Snapshot> CODEC=new PacketCodec<>(){
   public Snapshot decode(RegistryByteBuf b){int n=b.readableBytes();if(n>NameplateProtocol.MAX_BYTES){b.skipBytes(n);return new Snapshot(new byte[0]);}byte[] v=new byte[n];b.readBytes(v);return new Snapshot(v);}
   public void encode(RegistryByteBuf b,Snapshot p){b.writeBytes(p.bytes);}
  };public Id<Snapshot> getId(){return ID;}
 }
 public static void initialize(){
  net.fabricmc.fabric.api.resource.ResourceManagerHelper.get(net.minecraft.resource.ResourceType.CLIENT_RESOURCES).registerReloadListener(new net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener(){
   public Identifier getFabricId(){return Identifier.of("magiccodex","nameplates");}
   public void reload(net.minecraft.resource.ResourceManager manager){clear();}
  });
  PayloadTypeRegistry.playS2C().register(Snapshot.ID,Snapshot.CODEC);
  ClientPlayNetworking.registerGlobalReceiver(Snapshot.ID,(p,c)->{try{var list=NameplateProtocol.decode(p.bytes);c.client().execute(()->{var map=new HashMap<Integer,NameplateProtocol.Entry>();for(var e:list)map.put(e.entity(),e);entries=Map.copyOf(map);serverReady=true;received=Util.getMeasuringTimeMs();world=c.client().world;});}catch(IllegalArgumentException ignored){}});
  ClientPlayConnectionEvents.DISCONNECT.register((h,c)->clear());
  WorldRenderEvents.START.register(ctx->{var c=MinecraftClient.getInstance();if(c.world!=world){entries=Map.of();serverReady=false;world=c.world;}if(textures==null){textures=new HudTextureCache(c,true,true);text=new CodexTypography(c);}textures.beginFrame();text.beginFrame();ready=true;for(var s:STYLES)ready&=textures.worldReady(path(s));});
  WorldRenderEvents.AFTER_ENTITIES.register(ctx->{var c=MinecraftClient.getInstance();if(!ready||c.world==null||c.player==null||c.options.hudHidden)return;var matrices=ctx.matrixStack();var out=ctx.consumers();if(matrices==null||out==null)return;
   for(var data:entries.values()){Entity e=c.world.getEntityById(data.entity());if(e==null||data.name().isBlank()||!replacement(e)||!visible(e))continue;
    float delta=ctx.tickCounter().getTickDelta(false);var pos=e.getLerpedPos(delta).subtract(ctx.camera().getPos());double top=top(e);
    float h=NameplatePolicy.WORLD_WIDTH*768/NameplatePolicy.textureWidth(data.style());
    matrices.push();
    matrices.translate(pos.x,pos.y+top+.10+h/2,pos.z);matrices.multiply(ctx.camera().getRotation());
    textures.drawWorld(matrices,out,path(STYLES[data.style()]),NameplatePolicy.WORLD_WIDTH,h);
    // Safe rectangle is on the right of the flag: x=660..1830, y=205..505. Do not center text on the whole image.
    boolean titled=!data.titles().isEmpty();
    text.drawWorld(matrices,out,data.name(),221,NameplatePolicy.nameCenterY(titled),NameplatePolicy.NAME_SIZE,1100,0xFFF9F2DF);
    text.drawWorldTitles(matrices,out,data.titles(),221,-65);
    matrices.pop();
   }textures.endFrame();
  });
 }
 /** 이름표 자료에 실린 등록 닉네임 (#94: 닉네임 응답이 오기 전 화면 표시용). 없으면 "". */
 static String nickname(java.util.UUID id){if(id==null)return "";for(var d:entries.values())if(id.equals(d.uuid())&&d.name()!=null&&!d.name().isBlank())return d.name();return "";}
 private static String path(String style){return "magiccodex:textures/nameplates/nameplate_"+style+".png";}
 private static void clear(){entries=Map.of();serverReady=false;ready=false;world=null;if(textures!=null)textures.close();if(text!=null)text.close();textures=null;text=null;}
 /** Pending/stale tracked players must not leak an English vanilla account label while nickname data loads. */
 public static boolean suppressVanilla(Entity e){var c=MinecraftClient.getInstance();if(serverReady&&c.world==world&&c.getNetworkHandler()!=null&&e instanceof PlayerEntity&&c.getNetworkHandler().getPlayerListEntry(e.getUuid())!=null)return e!=c.player||selfPreview(c,e);var d=entries.get(e.getId());return c.world==world&&d!=null&&d.uuid().equals(e.getUuid())&&(e!=c.player||selfPreview(c,e));}
 public static boolean replacement(Entity e){var c=MinecraftClient.getInstance();var d=entries.get(e.getId());return ready&&c.world==world&&c.player!=null&&(e!=c.player||selfPreview(c,e))&&d!=null&&d.uuid().equals(e.getUuid())&&Util.getMeasuringTimeMs()-received<3000;}
 private static boolean selfPreview(MinecraftClient c,Entity e){return NameplatePolicy.selfPreview(c.options.getPerspective().isFirstPerson(),c.currentScreen instanceof HudCursorScreen,c.options.hudHidden,e.isAlive(),e.isInvisibleTo(c.player),e.isSpectator());}
 private static boolean visible(Entity e){
  var c=MinecraftClient.getInstance();if(e==c.player)return selfPreview(c,e);var team=e.getScoreboardTeam();var own=c.player.getScoreboardTeam();
  return NameplatePolicy.visible(e==c.player,e==c.getCameraEntity(),c.options.hudHidden,e.isAlive(),e.isInvisibleTo(c.player),e.isSpectator(),e.hasPassengers(),e.isSneaky(),e.squaredDistanceTo(c.gameRenderer.getCamera().getPos()),team==null?"ALWAYS":team.getNameTagVisibilityRule().name(),own!=null,team!=null&&team.isEqual(own));
 }
 private static double top(Entity e){
  if(!heightChecked){heightChecked=true;try{height=Class.forName("kr.chacademi.npcmodel.NpcModelClient").getMethod("modelTopHeight",Entity.class);}catch(ReflectiveOperationException|LinkageError ignored){}}
  if(height!=null)try{double value=((Number)height.invoke(null,e)).doubleValue();if(Double.isFinite(value)&&value>0&&value<32)return value;}catch(ReflectiveOperationException|LinkageError ignored){}
  return e.getHeight();
 }
}
