package school.magiccodex.client;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.function.Function;
import net.fabricmc.fabric.api.client.networking.v1.*;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import school.magiccodex.protocol.WalletProtocol;

public final class WalletClient {
    private static final DecimalFormat FORMAT=new DecimalFormat("#,##0",DecimalFormatSymbols.getInstance(Locale.US));
    static { FORMAT.setRoundingMode(java.math.RoundingMode.DOWN); }
    private static String label="—";
    private static long nextRequest,received;
    private WalletClient(){}
    public static String label(){return label;}
    private interface Bytes{byte[] bytes();}
    public record Request(byte[] bytes) implements CustomPayload,Bytes {
        public static final Id<Request> ID=new Id<>(Identifier.of(WalletProtocol.REQUEST));
        public static final PacketCodec<RegistryByteBuf,Request> CODEC=codec(Request::new,4);
        public Id<Request> getId(){return ID;}
    }
    public record Response(byte[] bytes) implements CustomPayload,Bytes {
        public static final Id<Response> ID=new Id<>(Identifier.of(WalletProtocol.RESPONSE));
        public static final PacketCodec<RegistryByteBuf,Response> CODEC=codec(Response::new,13);
        public Id<Response> getId(){return ID;}
    }
    private static <T extends Bytes> PacketCodec<RegistryByteBuf,T> codec(Function<byte[],T> factory,int size){
        return new PacketCodec<>(){
            public T decode(RegistryByteBuf buffer){
                if(buffer.readableBytes()!=size){buffer.skipBytes(buffer.readableBytes());return factory.apply(new byte[0]);}
                byte[] bytes=new byte[size];buffer.readBytes(bytes);return factory.apply(bytes);
            }
            public void encode(RegistryByteBuf buffer,T value){
                if(value.bytes().length!=size)throw new IllegalArgumentException("Invalid wallet payload");
                buffer.writeBytes(value.bytes());
            }
        };
    }
    public static void initialize(){
        PayloadTypeRegistry.playC2S().register(Request.ID,Request.CODEC);
        PayloadTypeRegistry.playS2C().register(Response.ID,Response.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(Response.ID,(payload,context)->{
            try{
                var snapshot=WalletProtocol.decode(payload.bytes());
                label=snapshot.available()?FORMAT.format(snapshot.balance()):"—";
                received=Util.getMeasuringTimeMs();
            }catch(IllegalArgumentException ignored){/* Ignore untrusted malformed data. */}
        });
        ClientPlayConnectionEvents.JOIN.register((handler,sender,client)->reset());
        ClientPlayConnectionEvents.DISCONNECT.register((handler,client)->reset());
    }
    private static void reset(){label="—";received=nextRequest=0;}
    public static void tick(MinecraftClient client){
        if(client.player==null || client.world==null)return;
        long now=Util.getMeasuringTimeMs();
        if(!ClientPlayNetworking.canSend(Request.ID)){reset();return;}
        if(received!=0 && now-received>65000){label="—";received=0;}
        if(now>=nextRequest && PlayerHudClient.active() && !client.options.hudHidden){
            ClientPlayNetworking.send(new Request(WalletProtocol.request()));nextRequest=now+20000;
        }
    }
}
