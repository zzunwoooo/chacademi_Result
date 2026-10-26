package school.magiccodex.client;

import java.util.function.Function;
import net.fabricmc.fabric.api.client.command.v2.*;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.*;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.*;
import school.magiccodex.protocol.AscensionProtocol;
import school.magiccodex.protocol.AscensionProtocol.*;

public final class AscensionClient {
    private static Response pending;
    private static int circle;
    private static long nextHello,lastSuccess;
    private interface Bytes{byte[] bytes();}
    public record Query(byte[] bytes) implements CustomPayload,Bytes{
        static final Id<Query> ID=new Id<>(Identifier.of(AscensionProtocol.REQUEST));
        static final PacketCodec<RegistryByteBuf,Query> CODEC=codec(Query::new);
        public Id<Query> getId(){return ID;}
    }
    public record Reply(byte[] bytes) implements CustomPayload,Bytes{
        static final Id<Reply> ID=new Id<>(Identifier.of(AscensionProtocol.RESPONSE));
        static final PacketCodec<RegistryByteBuf,Reply> CODEC=codec(Reply::new);
        public Id<Reply> getId(){return ID;}
    }
    private static <T extends Bytes> PacketCodec<RegistryByteBuf,T> codec(Function<byte[],T> factory){return new PacketCodec<>(){
        public T decode(RegistryByteBuf b){int n=b.readableBytes();if(n<4||n>AscensionProtocol.MAX_BYTES){b.skipBytes(n);return factory.apply(new byte[0]);}byte[] data=new byte[n];b.readBytes(data);return factory.apply(data);}
        public void encode(RegistryByteBuf b,T data){b.writeBytes(data.bytes());}
    };}
    public static int circle(){return circle;}
    static void claim(long token){if(ClientPlayNetworking.canSend(Query.ID))ClientPlayNetworking.send(new Query(AscensionProtocol.encodeRequest(new Request(AscensionProtocol.CLAIM,token))));}
    private static void receive(Response r){
        if(r.action()==AscensionProtocol.SUCCESS&&r.token()<=lastSuccess)return;
        circle=r.action()==AscensionProtocol.SUCCESS?r.to():r.from();PlayerHudClient.frame(circle);
        var c=MinecraftClient.getInstance();
        if(r.action()==AscensionProtocol.SNAPSHOT)return;
        if(r.action()==AscensionProtocol.SUCCESS)lastSuccess=r.token();
        if(c.currentScreen instanceof AscensionScreen s&&s.token()==r.token())s.receive(r);
        else if(r.action()!=AscensionProtocol.DENIED)pending=r;
    }
    public static void initialize(){
        PayloadTypeRegistry.playC2S().register(Query.ID,Query.CODEC);PayloadTypeRegistry.playS2C().register(Reply.ID,Reply.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(Reply.ID,(p,ctx)->{try{var r=AscensionProtocol.decodeResponse(p.bytes());ctx.client().execute(()->receive(r));}catch(IllegalArgumentException ignored){}});
        ClientPlayConnectionEvents.JOIN.register((h,s,c)->reset());ClientPlayConnectionEvents.DISCONNECT.register((h,c)->reset());
        ClientTickEvents.END_CLIENT_TICK.register(c->{
            if(c.player==null||c.world==null)return;
            long now=Util.getMeasuringTimeMs();
            if(circle==0&&now>=nextHello&&ClientPlayNetworking.canSend(Query.ID)){
                nextHello=now+3000;ClientPlayNetworking.send(new Query(AscensionProtocol.encodeRequest(new Request(AscensionProtocol.HELLO,0))));
            }
            if(pending!=null&&c.player.isAlive()&&c.getOverlay()==null&&(c.currentScreen==null||c.currentScreen instanceof ChatScreen||c.currentScreen instanceof HudCursorScreen)){
                MagicCodexClient.dismiss();var r=pending;pending=null;c.setScreen(new AscensionScreen(r,r.token()==0));
            }
        });
    }
    /** Server preview (/테스트 마법 승급화면 → "ascension [2..9]"). */
    static int preview(int to){to=Math.clamp(to,2,9);pending=new Response(AscensionProtocol.OFFER,0,to-1,to,true,"");return 1;}
    private static void reset(){circle=0;nextHello=lastSuccess=0;pending=null;}
    private AscensionClient(){}
}
