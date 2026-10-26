package school.magiccodex.protocol;
import java.nio.ByteBuffer;
/** Version 2 adds OFF=3. The legacy v1 query (target=0) is never reinterpreted. */
public final class GeneralPresetModeProtocol {
 public static final String REQUEST="magiccodex:general_preset_v2",RESPONSE="magiccodex:general_preset_state_v2";
 public static final int OFF=3;
 public record Request(long sequence,int target){} public record Response(long sequence,int selected,int status){}
 public static boolean valid(int mode){return mode>=1&&mode<=OFF;}
 public static byte[] encode(Request r){if(r.sequence()<1||r.target()<0||r.target()>OFF)throw new IllegalArgumentException();return ByteBuffer.allocate(10).put((byte)2).putLong(r.sequence()).put((byte)r.target()).array();}
 public static Request request(byte[] b){if(b==null||b.length!=10)throw new IllegalArgumentException();var x=ByteBuffer.wrap(b);if(x.get()!=2)throw new IllegalArgumentException();var r=new Request(x.getLong(),Byte.toUnsignedInt(x.get()));encode(r);return r;}
 public static byte[] encode(Response r){if(r.sequence()<1||!valid(r.selected())||r.status()<0||r.status()>3)throw new IllegalArgumentException();return ByteBuffer.allocate(11).put((byte)2).putLong(r.sequence()).put((byte)r.selected()).put((byte)r.status()).array();}
 public static Response response(byte[] b){if(b==null||b.length!=11)throw new IllegalArgumentException();var x=ByteBuffer.wrap(b);if(x.get()!=2)throw new IllegalArgumentException();var r=new Response(x.getLong(),Byte.toUnsignedInt(x.get()),Byte.toUnsignedInt(x.get()));encode(r);return r;}
 private GeneralPresetModeProtocol(){}
}
