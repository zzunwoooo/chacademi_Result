package school.magiccodex.client;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import com.google.gson.*;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.client.command.v2.*;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.*;
import net.fabricmc.fabric.api.client.networking.v1.*;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.text.Text;
import net.minecraft.util.*;
import school.magiccodex.protocol.PetProtocol;
import school.magiccodex.protocol.PetProtocol.*;

public final class PetClient {
    static final PetCatalogState STATE=new PetCatalogState();
    private static final Path ROOT=FabricLoader.getInstance().getConfigDir().resolve("magiccodex/pets");
    private static final ExecutorService IO=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"pet-preferences");t.setDaemon(true);return t;});
    private static PetModelRenderer models;
    private static Path favoritesFile;private static boolean favoritesReady;
    private static long sequence,waiting,lastRefresh,deadline,lastRequest;private static int epoch;
    private static boolean pendingOpen,preview,known;
    private static String notice="";private static long noticeAt;
    private static int modelGeneration=-1;
    private PetClient(){}
    public static Path modelRoot(){return ROOT.resolve("models");}
    private interface Bytes{byte[] bytes();}
    public record RequestPayload(byte[] bytes) implements CustomPayload,Bytes{
        public static final Id<RequestPayload> ID=new Id<>(Identifier.of(PetProtocol.REQUEST));
        public static final PacketCodec<RegistryByteBuf,RequestPayload> CODEC=codec(RequestPayload::new);
        public Id<RequestPayload> getId(){return ID;}
    }
    public record ResponsePayload(byte[] bytes) implements CustomPayload,Bytes{
        public static final Id<ResponsePayload> ID=new Id<>(Identifier.of(PetProtocol.RESPONSE));
        public static final PacketCodec<RegistryByteBuf,ResponsePayload> CODEC=codec(ResponsePayload::new);
        public Id<ResponsePayload> getId(){return ID;}
    }
    private static <T extends Bytes> PacketCodec<RegistryByteBuf,T> codec(Function<byte[],T> factory){return new PacketCodec<>(){
        public T decode(RegistryByteBuf b){if(b.readableBytes()>PetProtocol.MAX_BYTES){b.skipBytes(b.readableBytes());return factory.apply(new byte[0]);}byte[] bytes=new byte[b.readableBytes()];b.readBytes(bytes);return factory.apply(bytes);}
        public void encode(RegistryByteBuf b,T value){if(value.bytes().length>PetProtocol.MAX_BYTES)throw new IllegalArgumentException("Pet packet");b.writeBytes(value.bytes());}
    };}
    public static void initialize(){
        IO.execute(()->{try{
            Files.createDirectories(modelRoot().resolve("textures"));
            Path readme=ROOT.resolve("모델-넣는법.txt");if(!Files.exists(readme))Files.writeString(readme,"models/<MCPets의 펫ID>.bbmodel\n파일 이름이 펫ID와 다르면 models/aliases.json 에 {\"펫ID\":\"파일이름\"} 으로 연결할 수 있습니다(영문·숫자·_ . - 만).\n텍스처는 bbmodel에 포함해 저장하거나 models/textures/에 PNG를 넣으세요.\n/펫도감 새로고침 : 모델 다시 읽기\n큐브 모델, 그룹, 면 UV, 숫자 position/rotation/scale 키프레임을 지원합니다.\nidle 이름의 애니메이션 우선, 없으면 첫 애니메이션. 월드의 동행 펫은 걸을 때 walk/move/run, 날 때 fly/flying/glide 이름의 애니메이션을 씁니다(없으면 idle). Molang과 메시(mesh)는 지원하지 않습니다.\n");
        }catch(Exception e){log(e);}});
        PayloadTypeRegistry.playC2S().register(RequestPayload.ID,RequestPayload.CODEC);PayloadTypeRegistry.playS2C().register(ResponsePayload.ID,ResponsePayload.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(ResponsePayload.ID,(p,ctx)->{
            if(preview||waiting==0)return;
            try{var r=PetProtocol.response(p.bytes());if(r.sequence()!=waiting)return;if(STATE.accept(r)){waiting=0;known=true;lastRefresh=Util.getMeasuringTimeMs();if(!r.message().isEmpty())notice(r.message());}}
            catch(IllegalArgumentException e){waiting=0;notice("펫 목록을 읽지 못했습니다. 다시 열어 주세요.");}
        });
        ClientCommandRegistrationCallback.EVENT.register((d,registry)->{
            d.register(ClientCommandManager.literal("펫도감")
                .executes(c->{open();return 1;})
                .then(ClientCommandManager.literal("새로고침").executes(c->{reload();c.getSource().sendFeedback(Text.literal("펫 모델과 도감 정보를 다시 불러옵니다."));return 1;}))
            );
        });
        ClientPlayConnectionEvents.JOIN.register((h,s,c)->{reset();loadFavorites(c);});
        ClientPlayConnectionEvents.DISCONNECT.register((h,c)->reset());
        ClientLifecycleEvents.CLIENT_STOPPING.register(c->{if(models!=null)models.close();IO.shutdown();});
        ClientTickEvents.END_CLIENT_TICK.register(PetClient::tick);
    }
    private static void tick(MinecraftClient c){
        if(c.world==null||c.player==null)return;
        if(pendingOpen){pendingOpen=false;c.setScreen(new PetScreen());if(!preview)request(PetProtocol.LIST,"",false);}
        if(waiting!=0&&Util.getMeasuringTimeMs()>deadline){waiting=0;notice("응답을 기다리고 있습니다. 잠시 후 새로고침해 주세요.");}
        if(c.currentScreen==null&&c.getOverlay()==null)PetAssets.prepare();
    }
    static PetModelRenderer models(){if(models==null||modelGeneration!=UiResources.generation()){if(models!=null)models.close();models=new PetModelRenderer();modelGeneration=UiResources.generation();}return models;}
    public static void open(){if(preview){STATE.clearCatalog();known=false;lastRefresh=0;loadFavorites(MinecraftClient.getInstance());}preview=false;pendingOpen=true;}
    static boolean previewing(){return preview;}
    public static void invalidateCatalog(){known=false;lastRefresh=0;}
    static boolean busy(){return waiting!=0;}
    static String status(){if(waiting!=0)return "펫 정보를 불러오는 중…";if(!known&&!preview)return "서버의 펫 도감 연동을 기다리고 있습니다.";return "보유한 펫이 없습니다.";}
    static String notice(){return Util.getMeasuringTimeMs()-noticeAt<5000?notice:"";}
    static void notice(String message){notice=message;noticeAt=Util.getMeasuringTimeMs();}
    static boolean request(int action,String id,boolean force){
        long now=Util.getMeasuringTimeMs();
        if(preview){notice("미리보기에서는 실제 펫을 소환하지 않습니다.");return false;}
        if(waiting!=0)return false;
        if(action==PetProtocol.LIST&&!force&&known&&now-lastRefresh<30000)return false;
        if(now-lastRequest<1300){notice("잠시 후 다시 눌러 주세요.");return false;}
        if(!ClientPlayNetworking.canSend(RequestPayload.ID)){notice("서버의 MagicCodexBridge 펫 연동이 필요합니다.");return false;}
        lastRequest=now;waiting=++sequence;STATE.expect(waiting);deadline=now+8000;
        ClientPlayNetworking.send(new RequestPayload(PetProtocol.encode(new Request(waiting,action,id))));return true;
    }
    static void favorite(String id){
        if(!favoritesReady&&!preview){notice("즐겨찾기를 불러오는 중입니다.");return;}
        STATE.toggle(id);if(preview)return;
        Path file=favoritesFile;var values=STATE.favorites().stream().sorted().toList();
        IO.execute(()->{try{
            Files.createDirectories(file.getParent());Path temp=file.resolveSibling(file.getFileName()+".tmp");
            Files.writeString(temp,new Gson().toJson(values));try{Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING);}
        }catch(Exception e){log(e);MinecraftClient.getInstance().execute(()->notice("즐겨찾기를 저장하지 못했습니다."));}});
    }
    private static void loadFavorites(MinecraftClient c){
        favoritesReady=false;
        int current=epoch;String server=c.getCurrentServerEntry()==null?"singleplayer":c.getCurrentServerEntry().address;
        try{String key=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((server+"|"+c.player.getUuidAsString()).getBytes(StandardCharsets.UTF_8)));
            favoritesFile=ROOT.resolve("favorites").resolve(key+".json");Path file=favoritesFile;
            IO.execute(()->{
                Set<String> values=new HashSet<>();
                try{if(Files.isRegularFile(file)&&Files.size(file)<65536)for(var e:JsonParser.parseString(Files.readString(file)).getAsJsonArray())if(values.size()<512&&e.isJsonPrimitive()&&PetProtocol.validId(e.getAsString()))values.add(e.getAsString());}catch(Exception e){log(e);}
                c.execute(()->{if(epoch==current){STATE.favorites(values);favoritesReady=true;}});
            });
        }catch(Exception e){log(e);favoritesReady=false;}
    }
    private static void reset(){epoch++;STATE.reset();waiting=0;preview=false;known=false;pendingOpen=false;favoritesReady=false;lastRefresh=0;lastRequest=0;notice="";if(models!=null){models.close();models=null;}}
    static void reload(){if(models!=null){models.close();models=null;}PetCompanions.reloadModels();if(!preview)request(PetProtocol.LIST,"",true);}
    public static void preview(){fixture(List.of(new Entry("preview_fox","붉은 여우",3,true,false,new byte[0]),new Entry("preview_cat","검은 고양이",1,true,false,new byte[0]),new Entry("preview_wolf","회색 늑대",2,false,false,new byte[0])));}
    public static void fixture(List<Entry> entries){waiting=0;preview=true;known=true;STATE.expect(++sequence);for(var r:PetProtocol.split(sequence,"",entries))STATE.accept(r);}
    static void log(Exception e){org.slf4j.LoggerFactory.getLogger("magiccodex").warn("Pet config",e);}
}
