package school.magiccodex.client;
import java.util.*;
import net.fabricmc.fabric.api.client.networking.v1.*;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.resource.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.*;
import net.minecraft.entity.Entity;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.registry.Registries;
import net.minecraft.resource.*;
import net.minecraft.util.Identifier;
import school.magiccodex.protocol.ShinyProtocol;
import com.google.gson.*;
import java.nio.charset.StandardCharsets;

/** Only server packets mark entities. Original meshes/UV/animations are preserved. */
public final class ShinyClient {
 private static final Map<UUID,Integer> entities=new HashMap<>();
 private static final Map<String,Identifier> textures=new HashMap<>();private static final Set<String> failures=new HashSet<>();
 private static final Deque<String> context=new ArrayDeque<>();private static long pixels;
 private static JsonObject authoredTextures;
 public record Payload(byte[] bytes) implements CustomPayload {
  static final Id<Payload> ID=new Id<>(Identifier.of(ShinyProtocol.CHANNEL));
  static final PacketCodec<RegistryByteBuf,Payload> CODEC=new PacketCodec<>(){
   public Payload decode(RegistryByteBuf b){if(b.readableBytes()!=21){b.skipBytes(b.readableBytes());return new Payload(new byte[0]);}byte[] data=new byte[21];b.readBytes(data);return new Payload(data);}
   public void encode(RegistryByteBuf b,Payload p){b.writeBytes(p.bytes);}
  };public Id<Payload> getId(){return ID;}
 }
 public static void initialize(){
  PayloadTypeRegistry.playS2C().register(Payload.ID,Payload.CODEC);
  ClientPlayNetworking.registerGlobalReceiver(Payload.ID,(p,c)->{try{var s=ShinyProtocol.decode(p.bytes);c.client().execute(()->{if(s.shiny()){if(entities.size()<8192||entities.containsKey(s.uuid()))entities.put(s.uuid(),s.entityId());}else entities.remove(s.uuid());});}catch(IllegalArgumentException ignored){}});
  ClientPlayConnectionEvents.JOIN.register((h,s,c)->reset());ClientPlayConnectionEvents.DISCONNECT.register((h,c)->reset());
  net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents.ENTITY_UNLOAD.register((e,w)->entities.remove(e.getUuid()));
  ResourceManagerHelper.get(ResourceType.CLIENT_RESOURCES).registerReloadListener(new SimpleSynchronousResourceReloadListener(){
   public Identifier getFabricId(){return Identifier.of("magiccodex","shiny_textures");}
   public void reload(ResourceManager manager){clearTextures();}
  });
 }
 public static void begin(Entity e){context.push(entities.getOrDefault(e.getUuid(),-1)==e.getId()?Registries.ENTITY_TYPE.getId(e.getType()).getPath():"");}
 public static void end(){if(!context.isEmpty())context.pop();}
 public static boolean active(){return !context.isEmpty()&&!context.peek().isEmpty();}
 private static void reset(){entities.clear();context.clear();}
 private static void clearTextures(){var manager=MinecraftClient.getInstance().getTextureManager();for(var id:textures.values())manager.destroyTexture(id);textures.clear();failures.clear();pixels=0;authoredTextures=null;}
 private static Identifier authoredTexture(Identifier original){
  var manager=MinecraftClient.getInstance().getResourceManager();
  if(authoredTextures==null){
   authoredTextures=new JsonObject();
   var resource=manager.getResource(Identifier.of("magiccodex","vanilla-shiny.json"));
   if(resource.isPresent())try(var input=resource.get().getInputStream()){
    byte[] bytes=input.readNBytes(1_048_577);
    if(bytes.length<=1_048_576)authoredTextures=JsonParser.parseString(new String(bytes,StandardCharsets.UTF_8)).getAsJsonObject();
   }catch(Exception ignored){/* Missing/invalid authored resources retain the established fallback. */}
  }
  var type=authoredTextures.get(context.peek());if(type==null||!type.isJsonObject())return null;
  // Unmapped eyes, collars, clothing and effect layers keep their vanilla texture.
  var value=type.getAsJsonObject().get(original.toString());if(value==null)return original;if(!value.isJsonPrimitive())return null;
  try{
   var selected=Identifier.tryParse(value.getAsString());
   if(selected!=null&&selected.getNamespace().equals("magiccodex")&&selected.getPath().startsWith("textures/vanilla_shiny/")&&manager.getResource(selected).isPresent())return selected;
  }catch(RuntimeException ignored){}
  return null;
 }
 public static Identifier texture(Identifier original){
  if(!active()||!original.getNamespace().equals("minecraft")||!original.getPath().startsWith("textures/entity/")||original.getPath().contains("/equipment/")||original.getPath().contains("/armor/")||original.getPath().contains("/player/"))return original;
  var authored=authoredTexture(original);if(authored!=null)return authored;
  String key=context.peek()+"/"+original.getPath();var cached=textures.get(key);if(cached!=null)return cached;if(failures.contains(key)||textures.size()>=512)return original;
  var client=MinecraftClient.getInstance();
  try(var input=client.getResourceManager().getResourceOrThrow(original).getInputStream()){
   var image=NativeImage.read(input);long size=(long)image.getWidth()*image.getHeight();
   if(size>4_194_304||pixels+size>8_388_608){image.close();failures.add(key);return original;}
   float hue=palette(context.peek());image.apply(c->recolor(c,hue));
   var id=Identifier.of("magiccodex","shiny/"+key);client.getTextureManager().registerTexture(id,new NativeImageBackedTexture(image));textures.put(key,id);pixels+=size;return id;
  }catch(Exception ex){failures.add(key);return original;}
 }
 static float palette(String type){return switch(type){
  case "wolf","fox","rabbit","polar_bear"->.56f;
  case "zombie","husk","drowned","zombie_villager","zombified_piglin"->.76f;
  case "creeper","slime","magma_cube","breeze"->.91f;
  case "ender_dragon","wither","warden","enderman","endermite"->.12f;
  case "cow","mooshroom","pig","sheep","chicken"->.72f;
  case "blaze","ghast","piglin","piglin_brute","hoglin","zoglin"->.53f;
  case "guardian","elder_guardian","dolphin","squid","glow_squid"->.92f;
  case "iron_golem","snow_golem","ravager","evoker","illusioner"->.48f;
  default->Math.floorMod(type.hashCode(),360)/360f;
 };}
 public static int recolor(int color,float hue){
  int alpha=color>>>24;if(alpha==0)return color;
  float[] hsv=java.awt.Color.RGBtoHSB((color>>16)&255,(color>>8)&255,color&255,null);
  float saturation=Math.max(.36f,Math.min(.82f,hsv[1]*.72f+.22f));
  float value=Math.min(1,hsv[2]*.84f+.12f);
  int rgb=java.awt.Color.HSBtoRGB(hue+(hsv[0]-.5f)*.09f,saturation,value);
  return (alpha<<24)|(rgb&0xffffff);
 }
 private ShinyClient(){}
}
