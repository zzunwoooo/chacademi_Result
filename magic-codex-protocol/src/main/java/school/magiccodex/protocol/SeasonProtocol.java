package school.magiccodex.protocol;

import java.nio.ByteBuffer;

public final class SeasonProtocol {
    public static final String REQUEST="magiccodex:season_request", RESPONSE="magiccodex:season_response";
    private static final int VERSION=0x53454101;
    private SeasonProtocol(){}
    public static byte[] request(){return ByteBuffer.allocate(4).putInt(VERSION).array();}
    public static boolean validRequest(byte[] bytes){return bytes!=null&&bytes.length==4&&ByteBuffer.wrap(bytes).getInt()==VERSION;}
    public static byte[] encode(int season){if(season<0||season>3)throw new IllegalArgumentException("Invalid season");return ByteBuffer.allocate(5).putInt(VERSION).put((byte)season).array();}
    public static int decode(byte[] bytes){
        if(bytes==null||bytes.length!=5)throw new IllegalArgumentException("Invalid season packet");
        var b=ByteBuffer.wrap(bytes);if(b.getInt()!=VERSION)throw new IllegalArgumentException("Invalid season version");
        int value=b.get();if(value<0||value>3)throw new IllegalArgumentException("Invalid season");return value;
    }
}
