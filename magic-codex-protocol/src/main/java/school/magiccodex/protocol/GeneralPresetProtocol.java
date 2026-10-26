package school.magiccodex.protocol;
import java.nio.ByteBuffer;
/** A selection request never clears or edits any cooldown. Target 0 only queries. */
public final class GeneralPresetProtocol {
 public static final String REQUEST="magiccodex:general_preset",RESPONSE="magiccodex:general_preset_state";
 public static final int OK=0,COOLDOWN=1,COMBAT=2,UNAVAILABLE=3;
 public record Request(long sequence,int target){} public record Response(long sequence,int selected,int status){}
 public static byte[] encode(Request r){if(r.sequence()<1||r.target()<0||r.target()>2)throw new IllegalArgumentException();return ByteBuffer.allocate(10).put((byte)1).putLong(r.sequence()).put((byte)r.target()).array();}
 public static Request request(byte[] b){if(b.length!=10)throw new IllegalArgumentException();var x=ByteBuffer.wrap(b);if(x.get()!=1)throw new IllegalArgumentException();var r=new Request(x.getLong(),Byte.toUnsignedInt(x.get()));encode(r);return r;}
 public static byte[] encode(Response r){if(r.sequence()<1||r.selected()<1||r.selected()>2||r.status()<0||r.status()>3)throw new IllegalArgumentException();return ByteBuffer.allocate(11).put((byte)1).putLong(r.sequence()).put((byte)r.selected()).put((byte)r.status()).array();}
 public static Response response(byte[] b){if(b.length!=11)throw new IllegalArgumentException();var x=ByteBuffer.wrap(b);if(x.get()!=1)throw new IllegalArgumentException();var r=new Response(x.getLong(),Byte.toUnsignedInt(x.get()),Byte.toUnsignedInt(x.get()));encode(r);return r;}
 private GeneralPresetProtocol(){}
}
