package school.magiccodex.client;

import io.netty.buffer.Unpooled;
import java.io.*;
import java.util.*;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.registry.DynamicRegistryManager;
import org.junit.jupiter.api.Test;
import school.magiccodex.protocol.TitleProtocol;
import static org.junit.jupiter.api.Assertions.*;

class TitleProtocolV2ClientTest {
    private static RegistryByteBuf buffer(byte[] data){return new RegistryByteBuf(Unpooled.wrappedBuffer(data),DynamicRegistryManager.EMPTY);}
    private static List<TitleProtocol.Entry> catalogue(int count){
        var entries=new ArrayList<TitleProtocol.Entry>();
        for(int i=0;i<count;i++)entries.add(new TitleProtocol.Entry(i<290?"spell_title_"+i:"prefix_npc_npc_"+(i-290),i<290?i%2:0,"Title "+i,0x96e4ec));
        return entries;
    }
    @Test void serverEncodedLargeV2ReplyPassesClientCodecAboveOld24KiBLimit(){
        var expected=new TitleProtocol.Response(true,7,UUID.randomUUID().toString(),9,"Player","","","",catalogue(1200));
        byte[] serverWire=TitleProtocol.encode(expected);assertTrue(serverWire.length>24*1024);
        var input=buffer(serverWire);
        try{var received=TitleClient.Reply.CODEC.decode(input);assertArrayEquals(serverWire,received.bytes());assertEquals(expected,TitleProtocol.response(received.bytes()));assertEquals(0,input.readableBytes());}
        finally{input.release();}
    }
    @Test void independentlyWrittenServerWireKeeps290TitlesAnd40NpcEntries() throws Exception {
        String token=UUID.randomUUID().toString();var entries=catalogue(330);var bytes=new ByteArrayOutputStream();
        try(var wire=new DataOutputStream(bytes)){
            wire.writeInt(0x54495402);wire.writeBoolean(true);wire.writeLong(7);wire.writeUTF(token);wire.writeLong(9);
            wire.writeUTF("Player");wire.writeUTF("");wire.writeUTF("");wire.writeUTF("");wire.writeShort(entries.size());
            for(var entry:entries){wire.writeUTF(entry.id());wire.writeByte(entry.side());wire.writeUTF(entry.name());wire.writeInt(entry.color());}
        }
        var input=buffer(bytes.toByteArray());
        try{var decoded=TitleProtocol.response(TitleClient.Reply.CODEC.decode(input).bytes());assertEquals(new TitleProtocol.Response(true,7,token,9,"Player","","","",entries),decoded);}
        finally{input.release();}
    }
    @Test void requestAndReplyEnvelopesUseTheirOwnBoundsInBothDirections(){
        byte[] valid=TitleProtocol.encode(new TitleProtocol.Request(TitleProtocol.OPEN,1,"",0,"",""));
        var request=buffer(valid);
        try{assertArrayEquals(valid,TitleClient.Query.CODEC.decode(request).bytes());}finally{request.release();}
        var oversizedRequest=buffer(new byte[TitleProtocol.MAX_REQUEST_BYTES+1]);
        try{assertEquals(0,TitleClient.Query.CODEC.decode(oversizedRequest).bytes().length);assertEquals(0,oversizedRequest.readableBytes());}finally{oversizedRequest.release();}
        var oversizedReply=buffer(new byte[TitleProtocol.MAX_BYTES+1]);
        try{assertEquals(0,TitleClient.Reply.CODEC.decode(oversizedReply).bytes().length);assertEquals(0,oversizedReply.readableBytes());}finally{oversizedReply.release();}
        var output=new RegistryByteBuf(Unpooled.buffer(),DynamicRegistryManager.EMPTY);
        try{
            assertThrows(IllegalArgumentException.class,()->TitleClient.Query.CODEC.encode(output,new TitleClient.Query(new byte[TitleProtocol.MAX_REQUEST_BYTES+1])));
            assertThrows(IllegalArgumentException.class,()->TitleClient.Reply.CODEC.encode(output,new TitleClient.Reply(new byte[TitleProtocol.MAX_BYTES+1])));
            assertThrows(IllegalArgumentException.class,()->TitleClient.Query.CODEC.encode(output,new TitleClient.Query(new byte[3])));
            assertEquals(0,output.readableBytes());
        }finally{output.release();}
    }
    @Test void clientDoesNotAcceptV1OrTruncatedV2AsAnotherFormat(){
        var response=new TitleProtocol.Response(true,7,UUID.randomUUID().toString(),9,"Player","","","",catalogue(330));
        byte[] v2=TitleProtocol.encode(response);byte[] v1=v2.clone();v1[3]=1;
        assertThrows(IllegalArgumentException.class,()->TitleProtocol.response(v1));
        for(int n:new int[]{0,3,4,5,13,v2.length-1}){byte[] truncated=Arrays.copyOf(v2,n);assertThrows(IllegalArgumentException.class,()->TitleProtocol.response(truncated));}
    }
}
