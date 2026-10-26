package school.magiccodex.protocol;

import java.io.*;
import java.util.*;

/** Bounded, paged snapshots. Actions carry only a pet ID, never a command or target player. */
public final class PetProtocol {
    public static final String REQUEST="magiccodex:pet_request", RESPONSE="magiccodex:pet_response";
    public static final int LIST=1,SUMMON=2,DISMISS=3, MAX_BYTES=24000,MAX_PETS=512,MAX_ICON=8192;
    private static final int VERSION=0x50455403; // 02: Entry.info (펫 능력 설명), 03: Response.summary (도감 체력 한 줄) — 오너 2026-10-26
    public static final int MAX_SUMMARY=80;
    public static final int MAX_INFO=600;
    /** info: 펫 UI 에 마우스를 올렸을 때 보이는 능력 설명 (줄바꿈 \n, 최대 {@link #MAX_INFO}자). */
    public record Entry(String id,String name,int stars,boolean owned,boolean active,byte[] icon,String info){
        public Entry{if(!validId(id)||name==null||name.length()>96||stars<1||stars>3||icon.length>MAX_ICON||info==null||info.length()>MAX_INFO)throw new IllegalArgumentException("Pet");icon=icon.clone();}
        public Entry(String id,String name,int stars,boolean owned,boolean active,byte[] icon){this(id,name,stars,owned,active,icon,"");}
        @Override public byte[] icon(){return icon.clone();}
    }
    public record Request(long sequence,int action,String id){}
    /** summary: 펫 창 위쪽 한 줄 (예: "도감 체력 +8 · 점수 51 / 612"), 없으면 "". */
    public record Response(long sequence,int part,int parts,String message,String summary,List<Entry> entries){
        public Response{entries=List.copyOf(entries);if(summary==null||summary.length()>MAX_SUMMARY)throw new IllegalArgumentException("Summary");}
        public Response(long sequence,int part,int parts,String message,List<Entry> entries){this(sequence,part,parts,message,"",entries);}
    }
    public static boolean validId(String id){return id!=null&&id.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,95}")&&!id.contains("..");}
    private interface Writer{void write(DataOutputStream d)throws IOException;}
    private static byte[] out(Writer w){try{var b=new ByteArrayOutputStream();var d=new DataOutputStream(b);d.writeInt(VERSION);w.write(d);if(b.size()>MAX_BYTES)throw new IOException("Size");return b.toByteArray();}catch(IOException e){throw new IllegalArgumentException(e);}}
    private static DataInputStream in(byte[] bytes)throws IOException{if(bytes.length<4||bytes.length>MAX_BYTES)throw new IOException("Size");var d=new DataInputStream(new ByteArrayInputStream(bytes));if(d.readInt()!=VERSION)throw new IOException("Version");return d;}
    private static String str(DataInputStream d,int max)throws IOException{String s=d.readUTF();if(s.length()>max)throw new IOException("String");return s;}
    public static byte[] encode(Request r){if(r.sequence<=0||r.action<1||r.action>3||(r.action==LIST?!r.id.isEmpty():!validId(r.id)))throw new IllegalArgumentException("Request");return out(d->{d.writeLong(r.sequence);d.writeByte(r.action);d.writeUTF(r.id);});}
    public static Request request(byte[] bytes){try{var d=in(bytes);var r=new Request(d.readLong(),d.readUnsignedByte(),str(d,96));encode(r);if(d.available()!=0)throw new IOException("Trailing");return r;}catch(IOException e){throw new IllegalArgumentException(e);}}
    public static byte[] encode(Response r){return out(d->{if(r.sequence<=0||r.parts<1||r.parts>MAX_PETS||r.part<0||r.part>=r.parts||r.entries.size()>16||r.message.length()>180)throw new IOException("Response");d.writeLong(r.sequence);d.writeShort(r.part);d.writeShort(r.parts);d.writeUTF(r.message);d.writeUTF(r.summary);d.writeByte(r.entries.size());for(var e:r.entries){d.writeUTF(e.id);d.writeUTF(e.name);d.writeByte(e.stars);d.writeBoolean(e.owned);d.writeBoolean(e.active);d.writeShort(e.icon.length);d.write(e.icon);d.writeUTF(e.info);}});}
    public static Response response(byte[] bytes){try{var d=in(bytes);long seq=d.readLong();int part=d.readUnsignedShort(),parts=d.readUnsignedShort();String msg=str(d,180);String summary=str(d,MAX_SUMMARY);int n=d.readUnsignedByte();if(n>16)throw new IOException("Count");var list=new ArrayList<Entry>();for(int i=0;i<n;i++){String id=str(d,96),name=str(d,96);int stars=d.readUnsignedByte();boolean owned=d.readBoolean(),active=d.readBoolean();int len=d.readUnsignedShort();if(len>MAX_ICON||len>d.available())throw new IOException("Icon");byte[] icon=d.readNBytes(len);list.add(new Entry(id,name,stars,owned,active,icon,str(d,MAX_INFO)));}var r=new Response(seq,part,parts,msg,summary,list);encode(r);if(d.available()!=0)throw new IOException("Trailing");return r;}catch(IOException e){throw new IllegalArgumentException(e);}}
    public static List<Response> split(long seq,String message,List<Entry> entries){return split(seq,message,"",entries);}
    public static List<Response> split(long seq,String message,String summary,List<Entry> entries){
        if(entries.size()>MAX_PETS)throw new IllegalArgumentException("Count");
        var chunks=new ArrayList<List<Entry>>();var chunk=new ArrayList<Entry>();int bytes=600;
        for(var e:entries){int cost=620+e.icon.length+3*e.info.length();if(!chunk.isEmpty()&&(bytes+cost>MAX_BYTES||chunk.size()==16)){chunks.add(List.copyOf(chunk));chunk.clear();bytes=600;}chunk.add(e);bytes+=cost;}
        if(!chunk.isEmpty()||chunks.isEmpty())chunks.add(List.copyOf(chunk));
        var result=new ArrayList<Response>();for(int i=0;i<chunks.size();i++)result.add(new Response(seq,i,chunks.size(),message,summary,chunks.get(i)));return result;
    }
    private PetProtocol(){}
}

