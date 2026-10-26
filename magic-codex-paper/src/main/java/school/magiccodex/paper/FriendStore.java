package school.magiccodex.paper;

import java.nio.file.*;
import java.sql.*;
import java.util.*;
import school.magiccodex.protocol.SocialProtocol;
import school.magiccodex.database.ConnectionHolder;
import school.magiccodex.database.DatabaseSettings;

/** Owned by one IO worker. UUID relations survive renames/restarts. */
final class FriendStore implements AutoCloseable {
    private final ConnectionHolder holder;
    private Connection db()throws SQLException{return holder.get();}
    private final boolean maria;
    private final String friends,signals,requests,declines;
    /** 친구 신청 규칙: 보낸 신청 최대 20건, 7일 뒤 만료, 거절 후 같은 상대에게 10분 동안 재신청 불가. */
    static final int REQUEST_LIMIT=20;
    static final long REQUEST_TTL=7L*24*60*60*1000,DECLINE_COOLDOWN=10L*60*1000;
    enum Result{SENT,ACCEPTED,ALREADY_FRIENDS,DUPLICATE,COOLDOWN,LIMIT,FRIEND_LIMIT,TARGET_FRIEND_LIMIT,SELF,MISSING,DONE}
    record Snapshot(List<SocialProtocol.Entry> friends,List<SocialProtocol.Entry> incoming,List<SocialProtocol.Entry> outgoing){}
    record Polled(Map<UUID,List<SocialProtocol.Entry>> incoming,Map<UUID,Integer> friends){}
    FriendStore(Path file)throws Exception{this(file,new DatabaseSettings(false,"","",""));}
    FriendStore(Path file,DatabaseSettings settings)throws Exception{
        maria=settings.mariaDb();friends=settings.table("friends","friends");signals=settings.table("first_friend_signal","first_friend_signal");
        requests=settings.table("friend_requests","friend_requests");declines=settings.table("friend_declines","friend_declines");
        holder=settings.holder(file);
        try(var s=db().createStatement()){
            if(maria){
                s.execute("CREATE TABLE IF NOT EXISTS codex_friend_owners(owner CHAR(36) PRIMARY KEY) ENGINE=InnoDB");
                s.execute("CREATE TABLE IF NOT EXISTS codex_friends(id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,owner CHAR(36) NOT NULL,target CHAR(36) NOT NULL,name VARCHAR(64) NOT NULL,dorm VARCHAR(64) NOT NULL,UNIQUE KEY uq_friend(owner,target),KEY ix_friend_owner(owner,id),KEY ix_friend_target(target)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
                s.execute("CREATE TABLE IF NOT EXISTS codex_first_friend_signal(owner CHAR(36) PRIMARY KEY,delivered TINYINT NOT NULL DEFAULT 0) ENGINE=InnoDB");
                s.execute("CREATE TABLE IF NOT EXISTS codex_friend_requests(sender CHAR(36) NOT NULL,target CHAR(36) NOT NULL,name VARCHAR(64) NOT NULL,dorm VARCHAR(64) NOT NULL,target_name VARCHAR(64) NOT NULL,target_dorm VARCHAR(64) NOT NULL,created BIGINT NOT NULL,PRIMARY KEY(sender,target),KEY ix_friend_request_target(target)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
                s.execute("CREATE TABLE IF NOT EXISTS codex_friend_declines(sender CHAR(36) NOT NULL,target CHAR(36) NOT NULL,blocked_until BIGINT NOT NULL,PRIMARY KEY(sender,target)) ENGINE=InnoDB");
            }else{
                s.execute("CREATE TABLE IF NOT EXISTS friends(owner TEXT NOT NULL,target TEXT NOT NULL,name TEXT NOT NULL,dorm TEXT NOT NULL,PRIMARY KEY(owner,target))");
                s.execute("CREATE TABLE IF NOT EXISTS first_friend_signal(owner TEXT PRIMARY KEY,delivered INTEGER NOT NULL DEFAULT 0)");
                s.execute("CREATE TABLE IF NOT EXISTS friend_requests(sender TEXT NOT NULL,target TEXT NOT NULL,name TEXT NOT NULL,dorm TEXT NOT NULL,target_name TEXT NOT NULL,target_dorm TEXT NOT NULL,created INTEGER NOT NULL,PRIMARY KEY(sender,target))");
                s.execute("CREATE INDEX IF NOT EXISTS ix_friend_requests_target ON friend_requests(target)");
                s.execute("CREATE TABLE IF NOT EXISTS friend_declines(sender TEXT NOT NULL,target TEXT NOT NULL,blocked_until INTEGER NOT NULL,PRIMARY KEY(sender,target))");
            }
        }
    }
    List<SocialProtocol.Entry> load(UUID owner)throws SQLException{
        var list=new ArrayList<SocialProtocol.Entry>();
        try(var s=db().prepareStatement("SELECT target,name,dorm FROM "+friends+" WHERE owner=? ORDER BY "+(maria?"id":"rowid"))){
            s.setString(1,owner.toString());try(var r=s.executeQuery()){while(r.next())list.add(new SocialProtocol.Entry(UUID.fromString(r.getString(1)),r.getString(2),r.getString(3),false));}
        }
        return List.copyOf(list);
    }
    boolean add(UUID owner,SocialProtocol.Entry friend)throws SQLException{
        if(owner.equals(friend.id()))return false;
        holder.begin();
        try{
            if(maria){
                try(var s=db().prepareStatement("INSERT IGNORE INTO codex_friend_owners(owner) VALUES(?)")){s.setString(1,owner.toString());s.executeUpdate();}
                try(var s=db().prepareStatement("SELECT owner FROM codex_friend_owners WHERE owner=? FOR UPDATE")){s.setString(1,owner.toString());try(var r=s.executeQuery()){if(!r.next())throw new SQLException("Friend owner lock missing");}}
            }
            var all=load(owner);if(all.size()>=SocialProtocol.LIMIT||all.stream().anyMatch(e->e.id().equals(friend.id()))){holder.rollback();return false;}
            try(var s=db().prepareStatement("INSERT INTO "+friends+"(owner,target,name,dorm) VALUES(?,?,?,?)")){s.setString(1,owner.toString());s.setString(2,friend.id().toString());s.setString(3,friend.name());s.setString(4,friend.dormitory());s.executeUpdate();}
            try(var s=db().prepareStatement((maria?"INSERT IGNORE INTO ":"INSERT OR IGNORE INTO ")+signals+"(owner) VALUES(?)")){s.setString(1,owner.toString());s.executeUpdate();}
            db().commit();return true;
        }catch(SQLException e){holder.rollback();throw e;}finally{holder.end();}
    }
    boolean remove(UUID owner,UUID friend)throws SQLException{
        try(var s=db().prepareStatement("DELETE FROM "+friends+" WHERE owner=? AND target=?")){s.setString(1,owner.toString());s.setString(2,friend.toString());return s.executeUpdate()>0;}
    }
    void profile(UUID id,String name,String dorm)throws SQLException{
        try(var s=db().prepareStatement("UPDATE "+friends+" SET name=?,dorm=? WHERE target=? AND (name<>? OR dorm<>?)")){s.setString(1,name);s.setString(2,dorm);s.setString(3,id.toString());s.setString(4,name);s.setString(5,dorm);s.executeUpdate();}
        try(var s=db().prepareStatement("UPDATE "+requests+" SET name=?,dorm=? WHERE sender=? AND (name<>? OR dorm<>?)")){s.setString(1,name);s.setString(2,dorm);s.setString(3,id.toString());s.setString(4,name);s.setString(5,dorm);s.executeUpdate();}
        try(var s=db().prepareStatement("UPDATE "+requests+" SET target_name=?,target_dorm=? WHERE target=? AND (target_name<>? OR target_dorm<>?)")){s.setString(1,name);s.setString(2,dorm);s.setString(3,id.toString());s.setString(4,name);s.setString(5,dorm);s.executeUpdate();}
    }
    // ---- 친구 신청 (동의 기반). 수락되면 양방향 친구 행을 한 트랜잭션으로 기록한다. ----
    private interface Tx<T>{T run()throws SQLException;}
    private <T> T tx(Tx<T> body,UUID... owners)throws SQLException{
        holder.begin();
        try{lock(owners);T value=body.run();db().commit();return value;}
        catch(SQLException|RuntimeException e){holder.rollback();throw e;}finally{holder.end();}
    }
    /** MariaDB: 두 서버가 같은 두 사람을 동시에 처리하지 못하도록 소유자 행을 UUID 순서대로 잠근다. */
    private void lock(UUID... owners)throws SQLException{
        if(!maria)return;
        var sorted=new TreeSet<String>();for(UUID id:owners)sorted.add(id.toString());
        for(String owner:sorted){
            try(var s=db().prepareStatement("INSERT IGNORE INTO codex_friend_owners(owner) VALUES(?)")){s.setString(1,owner);s.executeUpdate();}
            try(var s=db().prepareStatement("SELECT owner FROM codex_friend_owners WHERE owner=? FOR UPDATE")){s.setString(1,owner);try(var r=s.executeQuery()){if(!r.next())throw new SQLException("Friend owner lock missing");}}
        }
    }
    private boolean has(UUID owner,UUID target)throws SQLException{try(var s=db().prepareStatement("SELECT 1 FROM "+friends+" WHERE owner=? AND target=?")){s.setString(1,owner.toString());s.setString(2,target.toString());try(var r=s.executeQuery()){return r.next();}}}
    private int count(UUID owner)throws SQLException{try(var s=db().prepareStatement("SELECT COUNT(*) FROM "+friends+" WHERE owner=?")){s.setString(1,owner.toString());try(var r=s.executeQuery()){return r.next()?r.getInt(1):0;}}}
    private void link(UUID owner,UUID target,String name,String dorm)throws SQLException{
        try(var s=db().prepareStatement("INSERT INTO "+friends+"(owner,target,name,dorm) VALUES(?,?,?,?)")){s.setString(1,owner.toString());s.setString(2,target.toString());s.setString(3,name);s.setString(4,dorm);s.executeUpdate();}
        try(var s=db().prepareStatement((maria?"INSERT IGNORE INTO ":"INSERT OR IGNORE INTO ")+signals+"(owner) VALUES(?)")){s.setString(1,owner.toString());s.executeUpdate();}
    }
    /** 대기 중인 신청의 생성 시각. 없으면 Long.MIN_VALUE. */
    private long pending(UUID sender,UUID target)throws SQLException{try(var s=db().prepareStatement("SELECT created FROM "+requests+" WHERE sender=? AND target=?")){s.setString(1,sender.toString());s.setString(2,target.toString());try(var r=s.executeQuery()){return r.next()?r.getLong(1):Long.MIN_VALUE;}}}
    private int pair(String table,UUID sender,UUID target)throws SQLException{try(var s=db().prepareStatement("DELETE FROM "+table+" WHERE sender=? AND target=?")){s.setString(1,sender.toString());s.setString(2,target.toString());return s.executeUpdate();}}
    /** a와 b를 서로 친구로 만든다. 이미 있는 방향은 그대로 두고, 한쪽이라도 가득 차면 아무것도 쓰지 않는다. */
    private Result befriend(UUID a,String aName,String aDorm,UUID b,String bName,String bDorm)throws SQLException{
        boolean ab=has(a,b),ba=has(b,a);
        if(!ab&&count(a)>=SocialProtocol.LIMIT)return Result.FRIEND_LIMIT;
        if(!ba&&count(b)>=SocialProtocol.LIMIT)return Result.TARGET_FRIEND_LIMIT;
        if(!ab)link(a,b,bName,bDorm);
        if(!ba)link(b,a,aName,aDorm);
        pair(requests,a,b);pair(requests,b,a);pair(declines,a,b);pair(declines,b,a);
        return Result.ACCEPTED;
    }
    /** sender가 target에게 친구 신청을 보낸다. 상대가 이미 나에게 신청했거나 나를 친구로 등록해 두었다면 즉시 서로 친구가 된다. */
    Result request(UUID sender,String senderName,String senderDorm,SocialProtocol.Entry target,long now)throws SQLException{
        if(sender.equals(target.id()))return Result.SELF;
        return tx(()->{
            boolean mine=has(sender,target.id()),theirs=has(target.id(),sender);
            if(mine&&theirs)return Result.ALREADY_FRIENDS;
            long cutoff=now-REQUEST_TTL,reverse=pending(target.id(),sender);
            if(theirs||(reverse!=Long.MIN_VALUE&&reverse>=cutoff))return befriend(sender,senderName,senderDorm,target.id(),target.name(),target.dormitory());
            long own=pending(sender,target.id());
            if(own!=Long.MIN_VALUE&&own>=cutoff)return Result.DUPLICATE;
            try(var s=db().prepareStatement("SELECT blocked_until FROM "+declines+" WHERE sender=? AND target=?")){s.setString(1,sender.toString());s.setString(2,target.id().toString());try(var r=s.executeQuery()){if(r.next()&&r.getLong(1)>now)return Result.COOLDOWN;}}
            try(var s=db().prepareStatement("SELECT COUNT(*) FROM "+requests+" WHERE sender=? AND created>=?")){s.setString(1,sender.toString());s.setLong(2,cutoff);try(var r=s.executeQuery()){if(r.next()&&r.getInt(1)>=REQUEST_LIMIT)return Result.LIMIT;}}
            if(!mine&&count(sender)>=SocialProtocol.LIMIT)return Result.FRIEND_LIMIT;
            pair(requests,sender,target.id()); // 만료된 같은 신청이 남아 있으면 교체
            try(var s=db().prepareStatement("INSERT INTO "+requests+"(sender,target,name,dorm,target_name,target_dorm,created) VALUES(?,?,?,?,?,?,?)")){s.setString(1,sender.toString());s.setString(2,target.id().toString());s.setString(3,senderName);s.setString(4,senderDorm);s.setString(5,target.name());s.setString(6,target.dormitory());s.setLong(7,now);s.executeUpdate();}
            return Result.SENT;
        },sender,target.id());
    }
    /** actor가 sender의 신청을 수락한다. 양방향 친구 행 기록과 신청 삭제가 한 트랜잭션이다. */
    Result accept(UUID actor,String actorName,String actorDorm,UUID sender,long now)throws SQLException{
        if(actor.equals(sender))return Result.MISSING;
        return tx(()->{
            String name="",dorm="";
            try(var s=db().prepareStatement("SELECT name,dorm,created FROM "+requests+" WHERE sender=? AND target=?")){s.setString(1,sender.toString());s.setString(2,actor.toString());try(var r=s.executeQuery()){if(!r.next()||r.getLong(3)<now-REQUEST_TTL)return Result.MISSING;name=r.getString(1);dorm=r.getString(2);}}
            return befriend(actor,actorName,actorDorm,sender,name,dorm);
        },actor,sender);
    }
    /** actor가 sender의 신청을 거절한다. 같은 상대의 재신청은 DECLINE_COOLDOWN 동안 막힌다. */
    boolean decline(UUID actor,UUID sender,long now)throws SQLException{
        return tx(()->{
            if(pair(requests,sender,actor)==0)return false;
            pair(declines,sender,actor);
            try(var s=db().prepareStatement("INSERT INTO "+declines+"(sender,target,blocked_until) VALUES(?,?,?)")){s.setString(1,sender.toString());s.setString(2,actor.toString());s.setLong(3,now+DECLINE_COOLDOWN);s.executeUpdate();}
            return true;
        },actor,sender);
    }
    /** 보낸 신청을 취소한다. 다른 서버의 수락과 겹치지 않도록 같은 잠금 아래에서 지운다. 신청→취소 반복으로 알림을 계속 띄우지 못하도록 거절과 같은 재신청 대기(sender→target 방향만)를 건다. */
    boolean withdraw(UUID sender,UUID target,long now)throws SQLException{
        return tx(()->{
            if(pair(requests,sender,target)==0)return false;
            pair(declines,sender,target);
            try(var s=db().prepareStatement("INSERT INTO "+declines+"(sender,target,blocked_until) VALUES(?,?,?)")){s.setString(1,sender.toString());s.setString(2,target.toString());s.setLong(3,now+DECLINE_COOLDOWN);s.executeUpdate();}
            return true;
        },sender,target);
    }
    /** 친구 삭제는 양쪽 목록에서 함께 지운다. */
    boolean unfriend(UUID a,UUID b)throws SQLException{
        try(var s=db().prepareStatement("DELETE FROM "+friends+" WHERE (owner=? AND target=?) OR (owner=? AND target=?)")){s.setString(1,a.toString());s.setString(2,b.toString());s.setString(3,b.toString());s.setString(4,a.toString());return s.executeUpdate()>0;}
    }
    /** 관리자: 이 유저가 받은·보낸 친구 신청을 모두 지운다 (거절 대기 기록 포함). 지운 신청 수. */
    int clearRequests(UUID owner)throws SQLException{
        int n;String id=owner.toString();
        try(var s=db().prepareStatement("DELETE FROM "+requests+" WHERE sender=? OR target=?")){s.setString(1,id);s.setString(2,id);n=s.executeUpdate();}
        try(var s=db().prepareStatement("DELETE FROM "+declines+" WHERE sender=? OR target=?")){s.setString(1,id);s.setString(2,id);s.executeUpdate();}
        return n;
    }
    List<SocialProtocol.Entry> incoming(UUID target,long now)throws SQLException{return requestList("SELECT sender,name,dorm FROM "+requests+" WHERE target=? AND created>=? ORDER BY created LIMIT "+SocialProtocol.LIMIT,target,now);}
    List<SocialProtocol.Entry> outgoing(UUID sender,long now)throws SQLException{return requestList("SELECT target,target_name,target_dorm FROM "+requests+" WHERE sender=? AND created>=? ORDER BY created LIMIT "+SocialProtocol.LIMIT,sender,now);}
    private List<SocialProtocol.Entry> requestList(String sql,UUID id,long now)throws SQLException{
        var list=new ArrayList<SocialProtocol.Entry>();
        try(var s=db().prepareStatement(sql)){s.setString(1,id.toString());s.setLong(2,now-REQUEST_TTL);try(var r=s.executeQuery()){while(r.next())list.add(new SocialProtocol.Entry(UUID.fromString(r.getString(1)),r.getString(2),r.getString(3),false));}}
        return List.copyOf(list);
    }
    Snapshot snapshot(UUID owner,long now)throws SQLException{return new Snapshot(load(owner),incoming(owner,now),outgoing(owner,now));}
    /** 만료된 신청과 지난 거절 기록 정리. */
    void purge(long now)throws SQLException{
        try(var s=db().prepareStatement("DELETE FROM "+requests+" WHERE created<?")){s.setLong(1,now-REQUEST_TTL);s.executeUpdate();}
        try(var s=db().prepareStatement("DELETE FROM "+declines+" WHERE blocked_until<=?")){s.setLong(1,now);s.executeUpdate();}
    }
    /** 공유 DB 모드: 접속 중인 플레이어들의 받은 신청과 친구 수를 묶어서 조회한다 (다른 서버에서 생긴 변화 감지용). */
    Polled poll(Collection<UUID> owners,long now)throws SQLException{
        var incoming=new HashMap<UUID,List<SocialProtocol.Entry>>();var counts=new HashMap<UUID,Integer>();
        var ids=new ArrayList<>(owners);
        for(int from=0;from<ids.size();from+=200){
            var part=ids.subList(from,Math.min(ids.size(),from+200));String marks=String.join(",",Collections.nCopies(part.size(),"?"));
            try(var s=db().prepareStatement("SELECT target,sender,name,dorm FROM "+requests+" WHERE created>=? AND target IN ("+marks+") ORDER BY created")){
                s.setLong(1,now-REQUEST_TTL);for(int i=0;i<part.size();i++)s.setString(i+2,part.get(i).toString());
                try(var r=s.executeQuery()){while(r.next()){var list=incoming.computeIfAbsent(UUID.fromString(r.getString(1)),k->new ArrayList<>());if(list.size()<SocialProtocol.LIMIT)list.add(new SocialProtocol.Entry(UUID.fromString(r.getString(2)),r.getString(3),r.getString(4),false));}}
            }
            try(var s=db().prepareStatement("SELECT owner,COUNT(*) FROM "+friends+" WHERE owner IN ("+marks+") GROUP BY owner")){
                for(int i=0;i<part.size();i++)s.setString(i+1,part.get(i).toString());
                try(var r=s.executeQuery()){while(r.next())counts.put(UUID.fromString(r.getString(1)),r.getInt(2));}
            }
        }
        return new Polled(incoming,counts);
    }
    boolean pendingSignal(UUID owner)throws SQLException{try(var s=db().prepareStatement("SELECT delivered FROM "+signals+" WHERE owner=?")){s.setString(1,owner.toString());try(var r=s.executeQuery()){return r.next()&&r.getInt(1)==0;}}}
    void signalDelivered(UUID owner)throws SQLException{try(var s=db().prepareStatement("UPDATE "+signals+" SET delivered=1 WHERE owner=?")){s.setString(1,owner.toString());s.executeUpdate();}}
    @Override public void close()throws SQLException{holder.close();}
}
