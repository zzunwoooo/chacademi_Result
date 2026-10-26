package school.magiccodex.protocol;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/** Clients send only a spell ID, never costs, commands, permissions or mana values. */
public final class ManaProtocol {
    public static final String REQUEST="magiccodex:mana_request", RESPONSE="magiccodex:mana_response";
    /** Server -> client: this spell's cooldown was cleared on the server (payload: US-ASCII spell id). */
    public static final String COOLDOWN_RESET="magiccodex:cooldown_reset";
    public static byte[] cooldownReset(String id){if(!id.matches("[a-z0-9_-]{1,64}"))throw new IllegalArgumentException("Invalid spell");return id.getBytes(StandardCharsets.US_ASCII);}
    public static String decodeCooldownReset(byte[] bytes){String id=new String(bytes,StandardCharsets.US_ASCII);if(!id.matches("[a-z0-9_-]{1,64}"))throw new IllegalArgumentException("Invalid spell");return id;}
    private static final int VERSION=0x4D414E01;
    public static final int SNAPSHOT=0, OK=1, UNKNOWN=2, LOCKED=3, EMPTY=4, COOLDOWN=5, FAILED=6, UNAVAILABLE=7;
    public record Snapshot(double current,double maximum,double regeneration,double haste) {
        public Snapshot(double current,double maximum,double regeneration){this(current,maximum,regeneration,0);}
        public Snapshot {
            MagicHaste.valid(haste);
            if(!Double.isFinite(current)||!Double.isFinite(maximum)||!Double.isFinite(regeneration)
                    ||current<0||maximum<0||current>maximum||regeneration<0||maximum>1_000_000||regeneration>1_000_000)
                throw new IllegalArgumentException("Invalid mana snapshot");
        }
    }
    public record Request(long sequence,String spell) {}
    public record Response(long sequence,int status,int cooldownMillis,Snapshot mana) {}
    private ManaProtocol(){}
    public static byte[] subscribe(){return ByteBuffer.allocate(4).putInt(VERSION).array();}
    public static byte[] cast(long sequence,String id){
        if(sequence<=0||!id.matches("[a-z0-9_-]{1,64}"))throw new IllegalArgumentException("Invalid cast");
        byte[] text=id.getBytes(StandardCharsets.US_ASCII);
        return ByteBuffer.allocate(12+text.length).putInt(VERSION).putLong(sequence).put(text).array();
    }
    public static Request decodeRequest(byte[] bytes){
        if(bytes.length!=4 && (bytes.length<13||bytes.length>76))throw new IllegalArgumentException("Invalid mana request size");
        var b=ByteBuffer.wrap(bytes);if(b.getInt()!=VERSION)throw new IllegalArgumentException("Invalid mana version");
        if(bytes.length==4)return new Request(0,"");
        long seq=b.getLong();byte[] text=new byte[b.remaining()];b.get(text);
        String id=new String(text,StandardCharsets.US_ASCII);
        if(seq<=0||!id.matches("[a-z0-9_-]{1,64}"))throw new IllegalArgumentException("Invalid cast ID");
        return new Request(seq,id);
    }
    public static byte[] encode(Response r){
        if(r.sequence()<0||r.status()<0||r.status()>UNAVAILABLE||r.cooldownMillis()<0||r.cooldownMillis()>86_400_000)
            throw new IllegalArgumentException("Invalid response");
        return ByteBuffer.allocate(49).putInt(0x4D414E02).putLong(r.sequence()).put((byte)r.status()).putInt(r.cooldownMillis())
                .putDouble(r.mana().current()).putDouble(r.mana().maximum()).putDouble(r.mana().regeneration()).putDouble(r.mana().haste()).array();
    }
    public static Response decode(byte[] bytes){
        if(bytes.length!=41&&bytes.length!=49)throw new IllegalArgumentException("Invalid mana response size");
        var b=ByteBuffer.wrap(bytes);int version=b.getInt();if(version!=(bytes.length==49?0x4D414E02:VERSION))throw new IllegalArgumentException("Invalid mana version");
        var r=new Response(b.getLong(),b.get(),b.getInt(),new Snapshot(b.getDouble(),b.getDouble(),b.getDouble(),bytes.length==49?b.getDouble():0));
        encode(r);return r;
    }
}
