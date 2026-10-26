package school.magiccodex.protocol;

import java.nio.ByteBuffer;

/** The client can only subscribe. Temperature is always selected by the server. */
public final class TemperatureProtocol {
    public static final String REQUEST="magiccodex:temperature_request", RESPONSE="magiccodex:temperature_response";
    private static final int VERSION=0x544D5001;
    public static final float MIN=-100, MAX=100;
    public record Snapshot(boolean available,float celsius){
        public Snapshot{validate(celsius);}
        public static Snapshot unavailable(){return new Snapshot(false,0);}
    }
    private TemperatureProtocol(){}
    public static void validate(float value){
        if(!Float.isFinite(value)||value<MIN||value>MAX)throw new IllegalArgumentException("온도는 -100~100°C 범위여야 합니다.");
    }
    public static byte[] request(){return ByteBuffer.allocate(4).putInt(VERSION).array();}
    public static boolean validRequest(byte[] data){return data!=null&&data.length==4&&ByteBuffer.wrap(data).getInt()==VERSION;}
    public static byte[] encode(Snapshot value){return ByteBuffer.allocate(9).putInt(VERSION).put((byte)(value.available()?1:0)).putFloat(value.celsius()).array();}
    public static Snapshot decode(byte[] data){
        if(data==null||data.length!=9)throw new IllegalArgumentException("Invalid temperature packet size");
        var b=ByteBuffer.wrap(data);
        if(b.getInt()!=VERSION)throw new IllegalArgumentException("Invalid temperature version");
        byte available=b.get();
        if(available!=0&&available!=1)throw new IllegalArgumentException("Invalid temperature availability");
        return new Snapshot(available==1,b.getFloat());
    }
}
