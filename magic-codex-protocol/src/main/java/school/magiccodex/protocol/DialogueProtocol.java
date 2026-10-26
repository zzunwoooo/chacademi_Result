package school.magiccodex.protocol;

import java.io.*;
import java.util.*;

/** Only session/choice IDs travel upstream. Conditions and effects are server-owned. */
public final class DialogueProtocol {
    public static final String REQUEST="magiccodex:dialogue_request", RESPONSE="magiccodex:dialogue_response";
    public static final int MAX_BYTES=30000;
    public record Request(String session,int sequence,String choice,boolean close){}
    public record Choice(String id,String text){}
    public record Response(String session,int sequence,boolean close,boolean preview,String title,String speaker,String portrait,String text,List<Choice> choices,String message){}
    public static byte[] encode(Request r){return write(o->{o.writeUTF(r.session);o.writeInt(r.sequence);o.writeUTF(r.choice);o.writeBoolean(r.close);});}
    public static Request request(byte[] b){return read(b,i->{String s=i.readUTF();int n=i.readInt();String c=i.readUTF();boolean close=i.readBoolean();if(!s.matches("[a-f0-9-]{36}")||n<0||!c.matches("[a-z0-9_-]{0,48}"))throw new IOException();return new Request(s,n,c,close);});}
    public static byte[] encode(Response r){return write(o->{o.writeUTF(r.session);o.writeInt(r.sequence);o.writeBoolean(r.close);o.writeBoolean(r.preview);o.writeUTF(r.title);o.writeUTF(r.speaker);o.writeUTF(r.portrait);o.writeUTF(r.text);o.writeInt(r.choices.size());for(var c:r.choices){o.writeUTF(c.id);o.writeUTF(c.text);}o.writeUTF(r.message);});}
    public static Response response(byte[] b){return read(b,i->{String s=i.readUTF();int seq=i.readInt();boolean close=i.readBoolean(),preview=i.readBoolean();String title=i.readUTF(),speaker=i.readUTF(),portrait=i.readUTF(),text=i.readUTF();int n=i.readInt();if(n<0||n>6||text.length()>1600||title.length()>140||speaker.length()>64||portrait.length()>160)throw new IOException();var cs=new ArrayList<Choice>();for(int k=0;k<n;k++){String id=i.readUTF(),label=i.readUTF();if(id.length()>48||label.length()>140)throw new IOException();cs.add(new Choice(id,label));}return new Response(s,seq,close,preview,title,speaker,portrait,text,List.copyOf(cs),i.readUTF());});}
    private interface Writer{void run(DataOutputStream o)throws IOException;}
    private interface Reader<T>{T run(DataInputStream i)throws IOException;}
    private static byte[] write(Writer f){try{var b=new ByteArrayOutputStream();var o=new DataOutputStream(b);o.writeInt(1);f.run(o);if(b.size()>MAX_BYTES)throw new IOException();return b.toByteArray();}catch(IOException e){throw new IllegalArgumentException("대화 패킷 크기/형식 오류",e);}}
    private static<T>T read(byte[] b,Reader<T> f){if(b.length>MAX_BYTES)throw new IllegalArgumentException();try{var i=new DataInputStream(new ByteArrayInputStream(b));if(i.readInt()!=1)throw new IOException();T r=f.run(i);if(i.available()!=0)throw new IOException();return r;}catch(IOException e){throw new IllegalArgumentException(e);}}
}
