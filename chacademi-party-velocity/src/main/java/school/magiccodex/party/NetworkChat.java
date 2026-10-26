package school.magiccodex.party;

import java.io.*;
import java.util.*;

/** Worker-owned authenticated identity cache and audience policy; no persistence or client claims. */
final class NetworkChat {
    static final long TTL=10_000, CHAT_INTERVAL=700;
    enum Channel { GLOBAL, HOUSE }
    record Connection(Object source,String server) {}
    record Identity(String nickname,int house,Object source,String server,long until) {}
    record WireIdentity(String nickname,int house,String registeredNickname) {WireIdentity(String nickname,int house){this(nickname,house,"");}}
    private final Set<String> trusted,chatServers;
    private final Map<UUID,Identity> identities=new HashMap<>();
    NetworkChat(Set<String> trusted){this(trusted,Set.of("school","wild1","wild2","arena"));}
    /** chatServers: 전체·기숙사 채팅을 쓰는 서버 이름 (party.properties chat-servers, 2026-10-21 하드코딩 제거). trusted 이기도 해야 한다. */
    NetworkChat(Set<String> trusted,Set<String> chatServers){this.trusted=Set.copyOf(trusted);this.chatServers=Set.copyOf(chatServers);}
    static WireIdentity decode(byte[] data)throws IOException {
        if(data.length>256)throw new IOException("Identity size");
        try(var in=new DataInputStream(new ByteArrayInputStream(data))){
            int version=in.readUnsignedByte();if(version!=1&&version!=2&&version!=3)throw new IOException("Identity version");
            String nickname=in.readUTF();int house=version>=2?in.readByte():-1;String registered=version==3?in.readUTF():"";
            if(registered.length()>32||registered.codePoints().anyMatch(c->Character.isISOControl(c)||Character.getType(c)==Character.FORMAT||c==0xA7)||in.available()!=0||house< -1||house>3||nickname.isBlank()||nickname.length()>32||nickname.codePoints().anyMatch(c->Character.isISOControl(c)||Character.getType(c)==Character.FORMAT||c==0xA7))throw new IOException("Identity values");
            return new WireIdentity(nickname,house,registered);
        }
    }
    boolean accept(UUID player,Object source,Object active,String server,boolean backendOwned,byte[] data,long now) {
        if(!backendOwned||source==null||source!=active||!trusted.contains(server))return false;
        try{var wire=decode(data);identities.put(player,new Identity(wire.nickname(),wire.house(),source,server,now+TTL));return true;}
        catch(IOException|RuntimeException e){return false;}
    }
    void disconnect(UUID player){identities.remove(player);}
    Identity identity(UUID player,Object active,long now){
        var identity=identities.get(player);return identity!=null&&identity.source()==active&&identity.until()>now?identity:null;
    }
    boolean networkServer(String server){return trusted.contains(server)&&chatServers.contains(server);}
    Identity sender(UUID actor,Map<UUID,Connection> online,long now){
        var connection=online.get(actor);
        if(connection==null||!networkServer(connection.server()))throw new IllegalArgumentException("학교·야생·아레나 서버에서 사용할 수 있습니다.");
        var identity=identity(actor,connection.source(),now);
        if(identity==null)throw new IllegalArgumentException("닉네임·기숙사 정보를 확인 중입니다. 잠시 후 다시 보내 주세요.");
        return identity;
    }
    Set<UUID> recipients(Channel channel,UUID actor,Map<UUID,Connection> online,long now){
        var sender=sender(actor,online,now);
        if(channel==Channel.HOUSE&&sender.house()<0)throw new IllegalArgumentException("소속 기숙사가 확인되지 않았습니다.");
        var result=new LinkedHashSet<UUID>();
        for(var entry:online.entrySet()){
            var c=entry.getValue();if(!networkServer(c.server()))continue;
            if(channel==Channel.HOUSE){var target=identity(entry.getKey(),c.source(),now);if(target==null||target.house()<0||target.house()!=sender.house())continue;}
            result.add(entry.getKey());
        }
        return result;
    }
    static void claimChat(Map<UUID,Long> next,UUID actor,long now){
        if(now<next.getOrDefault(actor,0L))throw new IllegalArgumentException("채팅을 너무 빠르게 보내고 있습니다.");
        next.put(actor,now+CHAT_INTERVAL);
    }
}
