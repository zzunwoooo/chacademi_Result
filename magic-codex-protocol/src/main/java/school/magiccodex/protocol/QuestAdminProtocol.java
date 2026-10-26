package school.magiccodex.protocol;

import java.io.*;
import java.util.*;

/** Permission is checked on the server before decoding any administrator document. */
public final class QuestAdminProtocol {
    /** List size the admin screens page through locally; responses are server-to-client so they may exceed the request cap. */
    public static final int MAX_ENTRIES=512,MAX_RESPONSE_BYTES=262144;
    public static final String REQUEST="magiccodex:quest_admin_request",RESPONSE="magiccodex:quest_admin_response";
    public record Request(int action,String id,String revision,Map<String,String> fields){} // list, load, save, delete
    public record Summary(String id,String title,String rank,String publication){}
    public record Response(String message,List<Summary> entries,String id,String revision,Map<String,String> fields){}
    public static byte[] encode(Request r){return write(QuestProtocol.MAX_BYTES,o->{o.writeByte(r.action);o.writeUTF(r.id);o.writeUTF(r.revision);map(o,r.fields);});}
    public static Request request(byte[] b){return read(b,QuestProtocol.MAX_BYTES,i->{int a=i.readUnsignedByte();String id=i.readUTF(),rev=i.readUTF();if(a>3||!id.matches("[a-z0-9_-]{0,48}")||rev.length()>64)throw new IOException();return new Request(a,id,rev,map(i));});}
    public static byte[] encode(Response r){if(r.entries.size()>MAX_ENTRIES)throw new IllegalArgumentException("관리 목록이 너무 깁니다 (최대 "+MAX_ENTRIES+"개)");return write(MAX_RESPONSE_BYTES,o->{o.writeUTF(r.message);o.writeInt(r.entries.size());for(var e:r.entries){o.writeUTF(e.id);o.writeUTF(e.title);o.writeUTF(e.rank);o.writeUTF(e.publication);}o.writeUTF(r.id);o.writeUTF(r.revision);map(o,r.fields);});}
    public static Response response(byte[] b){return read(b,MAX_RESPONSE_BYTES,i->{String msg=i.readUTF();int n=i.readInt();if(n<0||n>MAX_ENTRIES)throw new IOException();var list=new ArrayList<Summary>();for(int k=0;k<n;k++)list.add(new Summary(i.readUTF(),i.readUTF(),i.readUTF(),i.readUTF()));return new Response(msg,List.copyOf(list),i.readUTF(),i.readUTF(),map(i));});}
    private static void map(DataOutputStream o,Map<String,String> m)throws IOException{if(m.size()>100)throw new IllegalArgumentException("관리 문서 항목이 너무 많습니다");o.writeInt(m.size());for(var e:new TreeMap<>(m).entrySet()){if(e.getKey().length()>64||e.getValue().length()>16000)throw new IllegalArgumentException("관리 문서 항목이 너무 깁니다: "+e.getKey());o.writeUTF(e.getKey());o.writeUTF(e.getValue());}}
    private static Map<String,String> map(DataInputStream i)throws IOException{int n=i.readInt();if(n<0||n>100)throw new IOException();var out=new HashMap<String,String>();for(int k=0;k<n;k++){String key=i.readUTF(),value=i.readUTF();if(key.length()>64||value.length()>16000||out.put(key,value)!=null)throw new IOException();}return Map.copyOf(out);}
    private interface Writer{void run(DataOutputStream o)throws IOException;}
    private interface Reader<T>{T run(DataInputStream i)throws IOException;}
    private static byte[] write(int max,Writer f){try{var b=new ByteArrayOutputStream();var o=new DataOutputStream(b);o.writeInt(1);f.run(o);if(b.size()>max)throw new IllegalArgumentException("관리 문서가 너무 큽니다");return b.toByteArray();}catch(IOException e){throw new IllegalArgumentException(e);}}
    private static <T>T read(byte[] b,int max,Reader<T> f){if(b.length>max)throw new IllegalArgumentException();try{var i=new DataInputStream(new ByteArrayInputStream(b));if(i.readInt()!=1)throw new IOException();T r=f.run(i);if(i.available()!=0)throw new IOException();return r;}catch(IOException e){throw new IllegalArgumentException(e);}}
}
