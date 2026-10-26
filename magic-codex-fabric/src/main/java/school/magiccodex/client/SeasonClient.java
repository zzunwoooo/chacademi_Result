package school.magiccodex.client;

import net.fabricmc.fabric.api.client.networking.v1.*;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import school.magiccodex.protocol.SeasonProtocol;

public final class SeasonClient {
    private static long nextRequest;
    private static HudSeason serverSeason;
    private SeasonClient(){}
    public static String status(){return serverSeason==null?"서버 계절 응답 대기 (Bridge 0.13.1 연결 확인)":"서버 계절: "+serverSeason.label();}
    public record Request(byte[] bytes) implements CustomPayload {
        static final Id<Request> ID=new Id<>(Identifier.of(SeasonProtocol.REQUEST));
        static final PacketCodec<RegistryByteBuf,Request> CODEC=new PacketCodec<>(){
            public Request decode(RegistryByteBuf b){if(b.readableBytes()!=4){b.skipBytes(b.readableBytes());return new Request(new byte[0]);}byte[] v=new byte[4];b.readBytes(v);return new Request(v);}
            public void encode(RegistryByteBuf b,Request p){b.writeBytes(p.bytes());}
        };
        public Id<Request> getId(){return ID;}
    }
    public record Response(byte[] bytes) implements CustomPayload {
        static final Id<Response> ID=new Id<>(Identifier.of(SeasonProtocol.RESPONSE));
        static final PacketCodec<RegistryByteBuf,Response> CODEC=new PacketCodec<>(){
            public Response decode(RegistryByteBuf b){if(b.readableBytes()!=5){b.skipBytes(b.readableBytes());return new Response(new byte[0]);}byte[] v=new byte[5];b.readBytes(v);return new Response(v);}
            public void encode(RegistryByteBuf b,Response p){b.writeBytes(p.bytes());}
        };
        public Id<Response> getId(){return ID;}
    }
    public static void initialize(){
        PayloadTypeRegistry.playC2S().register(Request.ID,Request.CODEC);PayloadTypeRegistry.playS2C().register(Response.ID,Response.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(Response.ID,(payload,context)->{
            try{int season=SeasonProtocol.decode(payload.bytes());serverSeason=HudSeason.values()[season];TopMenuClient.season(serverSeason);}catch(IllegalArgumentException ignored){}
        });
        ClientPlayConnectionEvents.JOIN.register((h,s,c)->reset());ClientPlayConnectionEvents.DISCONNECT.register((h,c)->reset());
    }
    private static void reset(){nextRequest=0;serverSeason=null;TopMenuClient.season(HudSeason.SPRING);}
    public static void tick(MinecraftClient client){
        if(client.player==null||!ClientPlayNetworking.canSend(Request.ID))return;
        long now=Util.getMeasuringTimeMs();if(now>=nextRequest){ClientPlayNetworking.send(new Request(SeasonProtocol.request()));nextRequest=now+(serverSeason==null?2000:20000);}
    }
}
