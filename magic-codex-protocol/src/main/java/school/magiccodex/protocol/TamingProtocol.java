package school.magiccodex.protocol;

import java.io.*;
import java.util.UUID;

/** Small, bounded server-authoritative capture HUD messages. No client probability or reward input. */
public final class TamingProtocol {
    private TamingProtocol(){}
    public static final String REQUEST="magiccodex:taming_request", RESPONSE="magiccodex:taming_state";
    public static final String ANCHORED_RESPONSE="magiccodex:taming_anchored";
    public static final int MAX_BYTES=1600, QUERY=0, CAST=1, CANCEL=2;
    public static final int NONE=0,TARGET=1,CHANNEL=2,SUCCESS=3,FAIL=4,ERROR=5,PENDING=6;
    public record Request(int action,UUID target){}
    public record State(long token,int status,int entityId,String name,boolean boss,double chance,int graceMs,int remainingMs,int durationMs,String message){}
    /** Position belongs to the server-selected base, which ModelEngine may despawn client-side. */
    public record Anchor(UUID entityUuid,double x,double y,double z,double height) {
        public Anchor {
            if(entityUuid==null||!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z)
                    ||Math.abs(x)>32_000_000||Math.abs(y)>32_000_000||Math.abs(z)>32_000_000
                    ||!Double.isFinite(height)||height<0||height>64)throw new IllegalArgumentException("Taming anchor");
        }
    }
    public record AnchoredState(State state,Anchor anchor){}
    public static byte[] encode(AnchoredState value){return write(o->{
        byte[] state=encode(value.state);o.writeByte(1);o.writeShort(state.length);o.write(state);
        Anchor a=value.anchor;o.writeLong(a.entityUuid.getMostSignificantBits());o.writeLong(a.entityUuid.getLeastSignificantBits());
        o.writeDouble(a.x);o.writeDouble(a.y);o.writeDouble(a.z);o.writeDouble(a.height);
    });}
    public static AnchoredState anchoredState(byte[] raw){try(var i=input(raw)){
        if(i.readUnsignedByte()!=1)throw new IOException();int length=i.readUnsignedShort();
        if(length>MAX_BYTES||length>i.available())throw new IOException();
        State s=state(i.readNBytes(length));
        if(s.entityId<0||s.status==NONE||s.status==ERROR)throw new IOException();
        Anchor a=new Anchor(new UUID(i.readLong(),i.readLong()),i.readDouble(),i.readDouble(),i.readDouble(),i.readDouble());
        end(i);return new AnchoredState(s,a);
    }catch(IOException e){throw new IllegalArgumentException("Taming anchored state",e);}}
    public static byte[] encode(Request r){return write(o->{o.writeByte(1);o.writeByte(r.action);o.writeLong(r.target.getMostSignificantBits());o.writeLong(r.target.getLeastSignificantBits());});}
    public static Request request(byte[] raw){try(var i=input(raw)){if(i.readUnsignedByte()!=1)throw new IOException();int a=i.readUnsignedByte();if(a>2)throw new IOException();var r=new Request(a,new UUID(i.readLong(),i.readLong()));end(i);return r;}catch(IOException e){throw new IllegalArgumentException("Taming request",e);}}
    public static byte[] encode(State s){return write(o->{o.writeByte(1);o.writeLong(s.token);o.writeByte(s.status);o.writeInt(s.entityId);o.writeUTF(s.name);o.writeBoolean(s.boss);o.writeDouble(s.chance);o.writeInt(s.graceMs);o.writeInt(s.remainingMs);o.writeInt(s.durationMs);o.writeUTF(s.message);});}
    public static State state(byte[] raw){try(var i=input(raw)){if(i.readUnsignedByte()!=1)throw new IOException();var s=new State(i.readLong(),i.readUnsignedByte(),i.readInt(),i.readUTF(),i.readBoolean(),i.readDouble(),i.readInt(),i.readInt(),i.readInt(),i.readUTF());end(i);if(s.status>6||s.name.length()>96||s.message.length()>240||!Double.isFinite(s.chance)||s.chance<0||s.chance>1||s.graceMs<0||s.graceMs>3600000||s.remainingMs<0||s.remainingMs>60000||s.durationMs<0||s.durationMs>60000)throw new IOException();return s;}catch(IOException e){throw new IllegalArgumentException("Taming state",e);}}
    private static DataInputStream input(byte[] b)throws IOException{if(b.length>MAX_BYTES)throw new IOException();return new DataInputStream(new ByteArrayInputStream(b));}
    private static void end(DataInputStream i)throws IOException{if(i.available()!=0)throw new IOException();}
    private interface Writer{void run(DataOutputStream o)throws IOException;}
    private static byte[] write(Writer f){try{var b=new ByteArrayOutputStream();var o=new DataOutputStream(b);f.run(o);if(b.size()>MAX_BYTES)throw new IOException();return b.toByteArray();}catch(IOException e){throw new IllegalArgumentException(e);}}
}
