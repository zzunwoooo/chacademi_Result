package school.magiccodex.client;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.function.Function;
import java.util.concurrent.*;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import school.magiccodex.portrait.PortraitProtocol;

/** Own portrait (상대 일러스트는 {@link OtherPortraits}, 같은 채널). Disk IO, hashing, PNG decode and premultiplication run on IO;
 * texture upload/destruction and all Minecraft state run on the client thread. */
public final class PortraitClient {
    private interface Bytes { byte[] bytes(); }
    public record Query(byte[] bytes) implements CustomPayload, Bytes {
        static final Id<Query> ID=new Id<>(Identifier.of(PortraitProtocol.REQUEST));
        static final PacketCodec<RegistryByteBuf,Query> CODEC=codec(Query::new);
        public Id<Query> getId(){return ID;}
    }
    public record Reply(byte[] bytes) implements CustomPayload, Bytes {
        static final Id<Reply> ID=new Id<>(Identifier.of(PortraitProtocol.RESPONSE));
        static final PacketCodec<RegistryByteBuf,Reply> CODEC=codec(Reply::new);
        public Id<Reply> getId(){return ID;}
    }
    private static <T extends Bytes> PacketCodec<RegistryByteBuf,T> codec(Function<byte[],T> f){
        return new PacketCodec<>(){
            public T decode(RegistryByteBuf b){int n=b.readableBytes();if(n<4||n>PortraitProtocol.MAX_PACKET_BYTES){b.skipBytes(n);return f.apply(new byte[0]);}byte[] bytes=new byte[n];b.readBytes(bytes);return f.apply(bytes);}
            public void encode(RegistryByteBuf b,T p){b.writeBytes(p.bytes());}
        };
    }
    private static final Path ROOT=FabricLoader.getInstance().getConfigDir().resolve("magiccodex/portraits");
    private static final ThreadPoolExecutor IO=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(2),r->{Thread t=new Thread(r,"Portrait-IO");t.setDaemon(true);return t;},
            new ThreadPoolExecutor.DiscardOldestPolicy());
    private static final PortraitLoadFence LOAD=new PortraitLoadFence();
    private static boolean helloSent,ready,confirmed,loading,helloRetried;
    /** 불러오기가 시작된(또는 마지막으로 진행된) 시각. 0 = 불러오는 중 아님. 서버 응답이 없거나 작업이 밀려 사라지면 15초 뒤 정리한다. */
    private static long loadingSince;
    private static final long LOAD_TIMEOUT_MS=15_000L;
    private static String message="아직 저장된 일러스트가 없습니다.";
    private static PlayerPortraitTexture texture;
    private static String textureSha="";
    private static String incomingSha;
    private static int incomingTotal,incomingChunks,incomingNext;
    private static ByteArrayOutputStream incoming;
    private PortraitClient(){}
    static boolean ready(){return ready&&confirmed&&texture!=null;}
    /** 일러스트 서버와 HELLO 를 마쳤는지 (상대 일러스트 요청 가능). */
    static boolean serverReady(){return ready;}
    static boolean sendPacket(PortraitProtocol.Packet p){return send(p);}
    static boolean loading(){return loading;}
    static String status(){return loading?"일러스트를 불러오는 중…":!helloSent?"일러스트 서버에 연결되지 않았습니다.":message;}
    static String displayName(){var c=MinecraftClient.getInstance();return NicknameClient.display(c.player==null?"나":c.player.getName().getString());}
    static int imageWidth(){return texture==null?1:texture.width();}
    static int imageHeight(){return texture==null?1:texture.height();}
    static void draw(DrawContext c,int x,int y,int width){if(texture!=null)texture.draw(c,x,y,width);}
    /** My illustration's face in a circle; false when there is none (use the skin face). */
    static boolean drawFace(DrawContext c,float cx,float cy,float radius){if(!ready())return false;texture.drawFace(c,cx,cy,radius);return true;}
    static void drawTurn(DrawContext c){if(texture!=null)texture.drawTurn(c);}
    private static void reset(){
        OtherPortraits.reset();LOAD.next();IO.getQueue().clear();helloSent=ready=confirmed=loading=helloRetried=false;loadingSince=0;dropIncoming();
        if(texture!=null){texture.close();texture=null;}textureSha="";message="아직 저장된 일러스트가 없습니다.";
    }
    private static void dropIncoming(){incomingSha=null;incoming=null;incomingTotal=incomingChunks=incomingNext=0;}
    public static void initialize(){
        PayloadTypeRegistry.playC2S().register(Query.ID,Query.CODEC);
        PayloadTypeRegistry.playS2C().register(Reply.ID,Reply.CODEC);
        ClientCommandRegistrationCallback.EVENT.register((dispatcher,access)->
            dispatcher.register(ClientCommandManager.literal("내일러스트").executes(context->{
                DeferredScreens.open(PortraitPreviewScreen::new);return 1;
            })));
        ClientPlayConnectionEvents.JOIN.register((h,s,c)->c.execute(PortraitClient::reset));
        ClientPlayConnectionEvents.DISCONNECT.register((h,c)->c.execute(()->{
            reset();
            if(c.currentScreen instanceof PortraitPromptScreen screen)screen.serverClosed();
            if(c.currentScreen instanceof PortraitPreviewScreen)c.setScreen(null);
        }));
        ClientTickEvents.END_CLIENT_TICK.register(c->{
            if(!helloSent&&c.getNetworkHandler()!=null&&c.player!=null&&ClientPlayNetworking.canSend(Query.ID)){
                helloSent=true;queueLoad(c,cacheDir(c),"",null,true);
            }
            watchLoading(c);
        });
        ClientPlayNetworking.registerGlobalReceiver(Reply.ID,(payload,context)->{
            PortraitProtocol.Packet p;
            try{p=PortraitProtocol.decode(payload.bytes());}catch(IllegalArgumentException ignored){return;}
            var connection=context.client().getNetworkHandler();
            context.client().execute(()->{if(connection==context.client().getNetworkHandler())receive(context.client(),p);});
        });
    }
    private static void receive(MinecraftClient client,PortraitProtocol.Packet p){
        switch(p.op()){
            case PortraitProtocol.S_HELLO_ACK->ready=p.number()==PortraitProtocol.VERSION;
            case PortraitProtocol.S_META->{if(ready)meta(client,p);}
            case PortraitProtocol.S_CHUNK->{if(ready)chunk(client,p);}
            case PortraitProtocol.S_OTHER_META,PortraitProtocol.S_OTHER_CHUNK,PortraitProtocol.S_OTHER_BUSY->{if(ready)OtherPortraits.receive(p);}
            case PortraitProtocol.S_PROMPT_OPEN->{
                if(!ready||!PortraitProtocol.validToken(p.text())||client.player==null)return;
                String hint=p.extra().length()>200?p.extra().substring(0,200):p.extra();
                DeferredScreens.open(()->new PortraitPromptScreen(p.text(),hint));
            }
            case PortraitProtocol.S_PROMPT_CLOSE->{if(client.currentScreen instanceof PortraitPromptScreen screen)screen.serverClosed();}
            case PortraitProtocol.S_STATUS->{if(ready&&client.player!=null&&!p.extra().isEmpty())client.player.sendMessage(Text.literal(p.extra().substring(0,Math.min(200,p.extra().length()))),false);}
            default->{}
        }
    }
    /** 클라이언트 틱마다: 불러오기가 15초 넘게 멈춰 있으면 HELLO를 한 번 다시 보내고, 그래도 안 되면 그만둔다. */
    private static void watchLoading(MinecraftClient c){
        if(!loading){loadingSince=0;return;}
        long now=System.currentTimeMillis();
        if(loadingSince==0){loadingSince=now;return;}
        if(now-loadingSince<LOAD_TIMEOUT_MS)return;
        LOAD.next();dropIncoming();
        if(!helloRetried&&helloSent&&c.getNetworkHandler()!=null&&c.player!=null
                &&send(PortraitProtocol.Packet.of(PortraitProtocol.C_HELLO,texture!=null?textureSha:"",PortraitProtocol.VERSION,"magic-codex-ui"))){
            helloRetried=true;loadingSince=now;return;
        }
        loading=false;loadingSince=0;message="초상화를 불러오지 못했어요";
    }
    private static void meta(MinecraftClient c,PortraitProtocol.Packet p){
        String sha=p.text();
        if(sha.isEmpty()){
            LOAD.next();dropIncoming();loading=confirmed=false;message="아직 저장된 일러스트가 없습니다.";
            if(texture!=null){texture.close();texture=null;}textureSha="";
            Path dir=cacheDir(c);IO.execute(()->deleteCache(dir));return;
        }
        if(!PortraitProtocol.validSha(sha))return;
        int chunks;try{chunks=Integer.parseInt(p.extra());}catch(NumberFormatException e){return;}
        if(chunks==0){
            if(sha.equals(textureSha)){confirmed=true;loading=false;}
            else queueLoad(c,cacheDir(c),sha,null,false);
            return;
        }
        long total=p.number();
        if(total<=0||total>PortraitProtocol.MAX_IMAGE_BYTES||chunks!=PortraitProtocol.chunks((int)total))return;
        LOAD.next();loading=true;
        incomingSha=sha;incomingTotal=(int)total;incomingChunks=chunks;incomingNext=0;
        incoming=new ByteArrayOutputStream(incomingTotal);
    }
    private static void chunk(MinecraftClient c,PortraitProtocol.Packet p){
        if(incoming==null||!p.text().equals(incomingSha)||p.number()!=incomingNext)return;
        int expected=incomingNext==incomingChunks-1?incomingTotal-incomingNext*PortraitProtocol.CHUNK_BYTES:PortraitProtocol.CHUNK_BYTES;
        if(p.data().length!=expected){dropIncoming();loading=false;message="일러스트 전송을 확인하지 못했습니다.";return;}
        incoming.writeBytes(p.data());incomingNext++;if(loadingSince!=0)loadingSince=System.currentTimeMillis();
        if(incomingNext<incomingChunks)return;
        byte[] png=incoming.toByteArray();String sha=incomingSha;dropIncoming();
        queueLoad(c,cacheDir(c),sha,png,false);
    }
    private static void queueLoad(MinecraftClient c,Path dir,String expected,byte[] supplied,boolean hello){
        long ticket=LOAD.next();Object connection=c.getNetworkHandler();loading=true;
        IO.execute(()->{
            NativeImage decoded=null;PlayerTurnPortraitLayout.Bounds bounds=null;String sha=expected;
            try{
                if(!LOAD.current(ticket))return;
                byte[] png=supplied;
                if(png==null){
                    var cached=PortraitCache.read(dir,PortraitProtocol.MAX_IMAGE_BYTES);
                    if(cached!=null&&(sha.isEmpty()||sha.equals(cached.sha()))){sha=cached.sha();png=cached.png();}
                }
                if(png==null){
                    if(sha.isEmpty()){
                        Path hash=dir.resolve("portrait.sha");
                        if(Files.isRegularFile(hash)&&Files.size(hash)<=128)sha=Files.readString(hash).strip();
                    }
                    Path file=dir.resolve("portrait.png");
                    if(PortraitProtocol.validSha(sha)&&Files.isRegularFile(file)&&Files.size(file)<=PortraitProtocol.MAX_IMAGE_BYTES)png=Files.readAllBytes(file);
                }
                if(png!=null&&png.length<=PortraitProtocol.MAX_IMAGE_BYTES&&PortraitProtocol.validSha(sha)&&sha.equals(sha256(png))){
                    decoded=PlayerPortraitTexture.decode(png,PortraitProtocol.MAX_IMAGE_SIDE);
                    NativeImage scan=decoded;
                    bounds=PlayerTurnPortraitLayout.bounds(scan.getWidth(),scan.getHeight(),(x,y)->scan.getColorArgb(x,y)>>>24);

                }
            }catch(Exception ignored){}
            NativeImage image=decoded;var imageBounds=bounds;String hash=sha;
            c.execute(()->{
                if(!LOAD.current(ticket)||connection!=c.getNetworkHandler()||c.player==null){if(image!=null)image.close();return;}
                boolean installed=false;
                if(image!=null){
                    try{
                        var next=PlayerPortraitTexture.upload(image,imageBounds);
                        var previous=texture;
                        texture=next;textureSha=hash;installed=true;
                        if(previous!=null){try{previous.close();}catch(Exception ignored){}}
                        if(supplied!=null)IO.execute(()->{try{PortraitCache.write(dir,hash,supplied,()->LOAD.current(ticket));}catch(Exception ignored){}});
                    }catch(Exception error){message="일러스트를 불러오지 못했습니다.";}
                }
                if(hello){
                    if(!send(PortraitProtocol.Packet.of(PortraitProtocol.C_HELLO,installed?hash:"",PortraitProtocol.VERSION,"magic-codex-ui"))){
                        loading=false;message="일러스트 서버에 연결되지 않았습니다.";
                    }else if(loadingSince!=0)loadingSince=System.currentTimeMillis();
                }else{
                    confirmed=installed||confirmed;loading=false;
                    if(!installed)message="일러스트를 불러오지 못했습니다. 다시 접속해 주세요.";
                }
            });
        });
    }
    private static Path cacheDir(MinecraftClient c){
        var entry=c.getCurrentServerEntry();
        String server=entry==null?"local":entry.address.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9._-]","_");
        if(server.length()>80)server=server.substring(0,80);
        return ROOT.resolve(server).resolve(c.player==null?"unknown":c.player.getUuidAsString());
    }
    private static void deleteCache(Path dir){try{Files.deleteIfExists(dir.resolve("portrait.cache"));Files.deleteIfExists(dir.resolve("portrait.sha"));Files.deleteIfExists(dir.resolve("portrait.png"));}catch(Exception ignored){}}
    private static String sha256(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception e){return "";}}
    static boolean answer(String token,String text,boolean proceed){return send(new PortraitProtocol.Packet(PortraitProtocol.C_PROMPT,token,0,text,proceed,null));}
    private static boolean send(PortraitProtocol.Packet p){
        var c=MinecraftClient.getInstance();
        if(c.getNetworkHandler()==null||!ClientPlayNetworking.canSend(Query.ID))return false;
        try{ClientPlayNetworking.send(new Query(PortraitProtocol.encode(p)));return true;}catch(RuntimeException e){return false;}
    }
}
