package school.magiccodex.protocol;

import java.io.*;
import java.util.*;

/** Only slot indexes cross the trust boundary; item data is server-to-client only. */
public final class EquipmentProtocol {
    public static final String REQUEST="magiccodex:equipment_request",RESPONSE="magiccodex:equipment_response";
    public static final int MAX_BYTES=30000,VIEW=0,EQUIP=1,REMOVE=2,CLOSE=3;
    public record Request(int action,long sequence,long revision,int slot,int inventory) {
        public Request {if(action<0||action>3||sequence<=0||revision<0||slot<0||slot>7||inventory<0||inventory>35)throw new IllegalArgumentException("Invalid equipment request");}
    }
    public record Item(int inventory,int slot,byte[] preview) {
        public Item {if(inventory<0||inventory>35||slot<0||slot>7||preview.length>512)throw new IllegalArgumentException("Invalid item");}
    }
    public record Response(long sequence,long revision,int circle,String name,String message,double power,double health,double mana,double regen,List<byte[]> equipped,List<Item> candidates,double haste) {
        public Response(long sequence,long revision,int circle,String name,String message,double power,double health,double mana,double regen,List<byte[]> equipped,List<Item> candidates){this(sequence,revision,circle,name,message,power,health,mana,regen,equipped,candidates,0);}
    }
    public static byte[] request(Request r){return write(d->{d.writeInt(1);d.writeByte(r.action);d.writeLong(r.sequence);d.writeLong(r.revision);d.writeByte(r.slot);d.writeByte(r.inventory);});}
    public static Request request(byte[] b){return read(b,d->{if(d.readInt()!=1)throw new IOException();return new Request(d.readUnsignedByte(),d.readLong(),d.readLong(),d.readUnsignedByte(),d.readUnsignedByte());});}
    public static byte[] response(Response r){return write(d->{
        if(r.sequence<0||r.revision<0||r.circle<1||r.circle>9||r.equipped.size()!=8||r.candidates.size()>36)throw new IOException();
        d.writeInt(2);d.writeLong(r.sequence);d.writeLong(r.revision);d.writeByte(r.circle);string(d,r.name,64);string(d,r.message,200);
        for(double v:new double[]{r.power,r.health,r.mana,r.regen,r.haste}){if(!Double.isFinite(v)||v<0||v>1000000)throw new IOException();d.writeDouble(v);}
        for(byte[] p:r.equipped)bytes(d,p);
        d.writeByte(r.candidates.size());for(Item i:r.candidates){d.writeByte(i.inventory);d.writeByte(i.slot);bytes(d,i.preview);}
    });}
    public static Response response(byte[] b){return read(b,d->{
        int version=d.readInt();if(version!=1&&version!=2)throw new IOException();long seq=d.readLong(),rev=d.readLong();int circle=d.readUnsignedByte();
        String name=d.readUTF(),message=d.readUTF();double[] v={d.readDouble(),d.readDouble(),d.readDouble(),d.readDouble(),version==2?d.readDouble():0};
        if(seq<0||rev<0||circle<1||circle>9||name.length()>64||message.length()>200)throw new IOException();
        for(double n:v)if(!Double.isFinite(n)||n<0||n>1000000)throw new IOException();
        List<byte[]> eq=new ArrayList<>();for(int i=0;i<8;i++)eq.add(bytes(d));int count=d.readUnsignedByte();if(count>36)throw new IOException();
        List<Item> items=new ArrayList<>();Set<Integer> seen=new HashSet<>();for(int i=0;i<count;i++){Item item=new Item(d.readUnsignedByte(),d.readUnsignedByte(),bytes(d));if(!seen.add(item.inventory))throw new IOException();items.add(item);}
        return new Response(seq,rev,circle,name,message,v[0],v[1],v[2],v[3],List.copyOf(eq),List.copyOf(items),v[4]);
    });}
    private static void string(DataOutputStream d,String s,int max)throws IOException{if(s.length()>max)throw new IOException();d.writeUTF(s);}
    private static void bytes(DataOutputStream d,byte[] b)throws IOException{if(b.length>512)throw new IOException();d.writeShort(b.length);d.write(b);}
    private static byte[] bytes(DataInputStream d)throws IOException{int n=d.readUnsignedShort();if(n>512||n>d.available())throw new IOException();return d.readNBytes(n);}
    private interface Writer{void run(DataOutputStream d)throws IOException;}
    private interface Reader<T>{T run(DataInputStream d)throws IOException;}
    private static byte[] write(Writer w){try{var b=new ByteArrayOutputStream();w.run(new DataOutputStream(b));if(b.size()>MAX_BYTES)throw new IOException();return b.toByteArray();}catch(IOException e){throw new IllegalArgumentException("Invalid equipment packet",e);}}
    private static <T>T read(byte[] b,Reader<T> r){try{if(b.length>MAX_BYTES)throw new IOException();var d=new DataInputStream(new ByteArrayInputStream(b));T result=r.run(d);if(d.available()!=0)throw new IOException();return result;}catch(IOException e){throw new IllegalArgumentException("Invalid equipment packet",e);}}
}
