package school.magiccodex.client;

import java.util.*;
import java.util.function.Function;
import net.fabricmc.fabric.api.client.networking.v1.*;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.client.command.v2.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import school.magiccodex.protocol.DialogueProtocol;

public final class DialogueClient {
    private interface Bytes{byte[] bytes();}
    public record Query(byte[] bytes)implements CustomPayload,Bytes{static final Id<Query>ID=new Id<>(Identifier.of(DialogueProtocol.REQUEST));static final PacketCodec<RegistryByteBuf,Query>CODEC=codec(Query::new);public Id<Query>getId(){return ID;}}
    public record Reply(byte[] bytes)implements CustomPayload,Bytes{static final Id<Reply>ID=new Id<>(Identifier.of(DialogueProtocol.RESPONSE));static final PacketCodec<RegistryByteBuf,Reply>CODEC=codec(Reply::new);public Id<Reply>getId(){return ID;}}
    private static<T extends Bytes>PacketCodec<RegistryByteBuf,T>codec(Function<byte[],T>f){return new PacketCodec<>(){public T decode(RegistryByteBuf b){int n=b.readableBytes();if(n<4||n>DialogueProtocol.MAX_BYTES){b.skipBytes(n);return f.apply(new byte[0]);}byte[] bytes=new byte[n];b.readBytes(bytes);return f.apply(bytes);}public void encode(RegistryByteBuf b,T p){b.writeBytes(p.bytes());}};}
    public static void initialize(){PayloadTypeRegistry.playC2S().register(Query.ID,Query.CODEC);PayloadTypeRegistry.playS2C().register(Reply.ID,Reply.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(Reply.ID,(p,c)->{try{var r=DialogueProtocol.response(p.bytes());c.client().execute(()->{
            if(r.close()){if(held!=null&&held.session().equals(r.session()))held=null;if(c.client().currentScreen instanceof DialogueScreen screen&&!screen.external()&&screen.session().equals(r.session()))screen.serverClose();if(!r.message().isEmpty()&&c.client().player!=null)c.client().player.sendMessage(net.minecraft.text.Text.literal(r.message()),false);return;}
            // 외부(클라 주도) 대화가 우선: 그동안 온 서버 대화는 마지막 것 하나만 들고 있다가 외부 대화가 끝나면 연다 (ExternalDialogueHost 가 releaseHeld 를 부름).
            if(ExternalDialogueHost.busy(c.client())){held=r;return;}
            if(c.client().currentScreen instanceof DialogueScreen screen&&!screen.external()&&screen.session().equals(r.session()))screen.receive(r);
            else open(c.client(),r);
        });}catch(IllegalArgumentException ignored){}});
    }
    private static DialogueProtocol.Response held;
    private static void open(MinecraftClient client,DialogueProtocol.Response r){MagicCodexClient.dismiss();client.setScreen(new DialogueScreen(r,null));}
    /** 외부 대화가 끝난 뒤 미뤄 둔 서버 대화를 연다 (클라이언트 스레드). 서버 세션이 그사이 만료됐다면 서버가 닫기 응답으로 정리한다. */
    static void releaseHeld(MinecraftClient client){var r=held;if(r==null)return;held=null;if(client.player==null||client.world==null)return;open(client,r);}
    static void dropHeld(){held=null;}
    static boolean send(String session,int sequence,String choice,boolean close){if(MinecraftClient.getInstance().getNetworkHandler()==null||!ClientPlayNetworking.canSend(Query.ID))return false;ClientPlayNetworking.send(new Query(DialogueProtocol.encode(new DialogueProtocol.Request(session,sequence,choice,close))));return true;}
}
