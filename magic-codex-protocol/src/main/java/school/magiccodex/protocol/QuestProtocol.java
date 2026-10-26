package school.magiccodex.protocol;

import java.io.*;
import java.util.*;

/** Server sends display data only. Client requests never contain progress or rewards. */
public final class QuestProtocol {
    private QuestProtocol(){}
    public static final String REQUEST="magiccodex:quest_request", RESPONSE="magiccodex:quest_response";
    public static final int MAX_BYTES=30000, PAGE_SIZE=6, MAX_PAGE=100, MAX_TOTAL=PAGE_SIZE*MAX_PAGE, MAX_GOALS=6;
    public record Request(int action,int page,String id,int tab){public Request(int action,int page,String id){this(action,page,id,0);}} // 0 list, 1 accept, 2 claim, 3 abandon
    public record Goal(String label,int current,int required){}
    public record Card(String id,String title,String description,String reward,String state,List<Goal> goals,String rank,int completions,int limit,boolean main){public Card(String id,String title,String description,String reward,String state,List<Goal> goals,String rank,int completions,int limit){this(id,title,description,reward,state,goals,rank,completions,limit,false);}public Card(String id,String title,String description,String reward,String state,List<Goal> goals){this(id,title,description,reward,state,goals,"F",0,1,false);}}
    public record Response(boolean open,int page,int total,String message,List<Card> cards,int remainingSubquests){public Response(boolean open,int page,int total,String message,List<Card> cards){this(open,page,total,message,cards,3);}}
    public static byte[] encode(Request r){if(r.action<0||r.action>3||r.page<0||r.page>MAX_PAGE||r.tab<0||r.tab>2)throw new IllegalArgumentException("Quest request");return write(o->{o.writeInt(2);o.writeByte(r.action);o.writeInt(r.page);o.writeUTF(r.id);o.writeByte(r.tab);});}
    public static Request request(byte[] b){return read(b,i->{version(i);int a=i.readUnsignedByte(),p=i.readInt();String id=i.readUTF();if(a>3||p<0||p>MAX_PAGE||!id.matches("[a-z0-9_-]{0,48}"))throw new IOException();int tab=i.readUnsignedByte();if(tab>2)throw new IOException();return new Request(a,p,id,tab);});}
    public static byte[] encode(Response r){validate(r);return write(o->{o.writeInt(3);o.writeBoolean(r.open);o.writeInt(r.page);o.writeInt(r.total);o.writeUTF(r.message);o.writeInt(r.remainingSubquests);o.writeInt(r.cards.size());for(var c:r.cards){o.writeUTF(c.id);o.writeUTF(c.title);o.writeUTF(c.description);o.writeUTF(c.reward);o.writeUTF(c.state);o.writeUTF(c.rank);o.writeInt(c.completions);o.writeInt(c.limit);o.writeBoolean(c.main);o.writeInt(c.goals.size());for(var g:c.goals){o.writeUTF(g.label);o.writeInt(g.current);o.writeInt(g.required);}}});}
    public static Response response(byte[] b){return read(b,i->{int version=i.readInt();if(version!=2&&version!=3)throw new IOException();boolean open=i.readBoolean();int page=i.readInt(),total=i.readInt();String msg=i.readUTF();int remaining=version>=3?i.readInt():3;if(remaining<0||remaining>3)throw new IOException();int n=i.readInt();if(n<0||n>PAGE_SIZE||page<0||total<0||total>MAX_TOTAL)throw new IOException();var cards=new ArrayList<Card>();for(int k=0;k<n;k++){String id=i.readUTF(),title=i.readUTF(),desc=i.readUTF(),reward=i.readUTF(),state=i.readUTF();String rank=i.readUTF();int completed=i.readInt(),limit=i.readInt();if(!rank.matches("[A-F]")||completed<0||limit<1)throw new IOException();boolean main=version>=3&&i.readBoolean();int m=i.readInt();if(m<0||m>MAX_GOALS)throw new IOException();var goals=new ArrayList<Goal>();for(int j=0;j<m;j++){String label=i.readUTF();int current=i.readInt(),required=i.readInt();if(required<1||current<0||current>required)throw new IOException();goals.add(new Goal(label,current,required));}cards.add(new Card(id,title,desc,reward,state,List.copyOf(goals),rank,completed,limit,main));}return new Response(open,page,total,msg,List.copyOf(cards),remaining);});}
    /** Mirrors {@link #response(byte[])} so oversize boards fail on the server instead of being dropped by the client. */
    private static void validate(Response r){
        if(r.remainingSubquests<0||r.remainingSubquests>3||r.cards.size()>PAGE_SIZE||r.page<0||r.total<0||r.total>MAX_TOTAL)throw new IllegalArgumentException("Quest board too large");
        for(var c:r.cards){if(!c.rank.matches("[A-F]")||c.completions<0||c.limit<1||c.goals.size()>MAX_GOALS)throw new IllegalArgumentException("Quest card");for(var g:c.goals)if(g.required<1||g.current<0||g.current>g.required)throw new IllegalArgumentException("Quest goal");}
    }
    private static void version(DataInputStream i)throws IOException{if(i.readInt()!=2)throw new IOException();}
    private interface Writer{void run(DataOutputStream o)throws IOException;}
    private interface Reader<T>{T run(DataInputStream i)throws IOException;}
    private static byte[] write(Writer f){try{var b=new ByteArrayOutputStream();f.run(new DataOutputStream(b));if(b.size()>MAX_BYTES)throw new IllegalArgumentException("Quest packet too large");return b.toByteArray();}catch(IOException e){throw new IllegalArgumentException(e);}}
    private static <T>T read(byte[] b,Reader<T> f){if(b.length>MAX_BYTES)throw new IllegalArgumentException();try{var i=new DataInputStream(new ByteArrayInputStream(b));T r=f.run(i);if(i.available()!=0)throw new IOException();return r;}catch(IOException e){throw new IllegalArgumentException(e);}}
}
