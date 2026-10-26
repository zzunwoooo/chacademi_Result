package school.magiccodex.protocol;
import java.nio.ByteBuffer;
import java.util.UUID;
public final class ShinyProtocol {
 public static final String CHANNEL="magiccodex:shiny";
 public record State(int entityId,UUID uuid,boolean shiny){}
 public static byte[] encode(State s){return ByteBuffer.allocate(21).putInt(s.entityId()).putLong(s.uuid().getMostSignificantBits()).putLong(s.uuid().getLeastSignificantBits()).put((byte)(s.shiny()?1:0)).array();}
 public static State decode(byte[] bytes){if(bytes.length!=21)throw new IllegalArgumentException("shiny packet length");var b=ByteBuffer.wrap(bytes);int id=b.getInt();var uuid=new UUID(b.getLong(),b.getLong());byte value=b.get();if(value<0||value>1)throw new IllegalArgumentException("shiny flag");return new State(id,uuid,value==1);}
 private ShinyProtocol(){}
}
