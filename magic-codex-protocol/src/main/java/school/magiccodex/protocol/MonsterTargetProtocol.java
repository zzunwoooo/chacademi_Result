package school.magiccodex.protocol;
import java.io.*;
/** Optional new channel; legacy taming packets and capture authority remain unchanged. */
public final class MonsterTargetProtocol {
 public static final String CHANNEL="magiccodex:monster_target";
 public record Target(TamingProtocol.AnchoredState target,double health,double maximum,boolean shiny,boolean tameable,boolean custom){public Target{if(target==null||!Double.isFinite(health)||!Double.isFinite(maximum)||health<0||maximum<=0||health>maximum+1)throw new IllegalArgumentException("Monster health");}}
 public static byte[] encode(Target value){try{var b=new ByteArrayOutputStream();var o=new DataOutputStream(b);byte[] t=TamingProtocol.encode(value.target());o.writeByte(1);o.writeShort(t.length);o.write(t);o.writeDouble(value.health());o.writeDouble(value.maximum());o.writeBoolean(value.shiny());o.writeBoolean(value.tameable());o.writeBoolean(value.custom());if(b.size()>TamingProtocol.MAX_BYTES)throw new IOException();return b.toByteArray();}catch(IOException e){throw new IllegalArgumentException(e);}}
 public static Target decode(byte[] raw){try{if(raw.length>TamingProtocol.MAX_BYTES)throw new IOException();var i=new DataInputStream(new ByteArrayInputStream(raw));if(i.readUnsignedByte()!=1)throw new IOException();int n=i.readUnsignedShort();if(n>i.available())throw new IOException();var result=new Target(TamingProtocol.anchoredState(i.readNBytes(n)),i.readDouble(),i.readDouble(),i.readBoolean(),i.readBoolean(),i.readBoolean());if(i.available()!=0)throw new IOException();return result;}catch(IOException e){throw new IllegalArgumentException(e);}}
}
