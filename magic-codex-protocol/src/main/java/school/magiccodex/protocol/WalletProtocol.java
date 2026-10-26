package school.magiccodex.protocol;

import java.nio.ByteBuffer;

/** Fixed-size, read-only balance channel. It never accepts amounts or commands from clients. */
public final class WalletProtocol {
    public static final String REQUEST="magiccodex:wallet_request",RESPONSE="magiccodex:wallet_response";
    private static final int VERSION=0x574C5401;
    public record Snapshot(boolean available,double balance){
        public Snapshot{if(!Double.isFinite(balance))throw new IllegalArgumentException("Invalid balance");}
        public static Snapshot unavailable(){return new Snapshot(false,0);}
    }
    private WalletProtocol(){}
    public static byte[] request(){return ByteBuffer.allocate(4).putInt(VERSION).array();}
    public static boolean validRequest(byte[] data){return data.length==4 && ByteBuffer.wrap(data).getInt()==VERSION;}
    public static byte[] encode(Snapshot value){
        return ByteBuffer.allocate(13).putInt(VERSION).put((byte)(value.available()?1:0)).putDouble(value.balance()).array();
    }
    public static Snapshot decode(byte[] data){
        if(data.length!=13)throw new IllegalArgumentException("Invalid wallet packet size");
        var buffer=ByteBuffer.wrap(data);
        if(buffer.getInt()!=VERSION)throw new IllegalArgumentException("Invalid wallet version");
        byte available=buffer.get();
        if(available!=0 && available!=1)throw new IllegalArgumentException("Invalid availability");
        return new Snapshot(available==1,buffer.getDouble());
    }
}
