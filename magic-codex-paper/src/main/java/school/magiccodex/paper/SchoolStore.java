package school.magiccodex.paper;

import java.nio.file.*;
import java.sql.*;
import java.util.*;
import school.magiccodex.protocol.SchoolProtocol.Donation;
import school.magiccodex.database.ConnectionHolder;
import school.magiccodex.database.DatabaseSettings;

/** Single IO worker owns this database; record claim and house points commit together. */
final class SchoolStore implements AutoCloseable {
    static final long MAX_SCORE=1_000_000_000L;
    record Snapshot(List<Long> scores,List<Donation> records,Map<UUID,Integer> houses,Set<String> blocked){
        Snapshot(List<Long> scores,List<Donation> records,Map<UUID,Integer> houses){this(scores,records,houses,Set.of());}
        Snapshot{scores=List.copyOf(scores);records=List.copyOf(records);houses=Map.copyOf(houses);blocked=Set.copyOf(blocked);}
    }
    private final ConnectionHolder holder;
    private Connection db()throws SQLException{return holder.get();}
    private final boolean maria;
    private final String points,donations,houses,policy,presets;
    static final class DonationBlocked extends SQLException {DonationBlocked(){super("Donation is blocked by administrator policy");}}
    SchoolStore(Path file)throws Exception{this(file,new DatabaseSettings(false,"","",""));}
    SchoolStore(Path file,DatabaseSettings settings)throws Exception{
        maria=settings.mariaDb();points=settings.table("house_points","house_points");donations=settings.table("donations","donations");houses=settings.table("houses","houses");
        policy=settings.table("donation_policy","donation_policy");presets=settings.table("house_presets","house_presets");
        holder=settings.holder(file);
        try(var s=db().createStatement()){
            s.execute("CREATE TABLE IF NOT EXISTS "+policy+"(spell VARCHAR(64) PRIMARY KEY,blocked INT NOT NULL DEFAULT 0)"+(maria?" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4":""));
            if(maria){
                s.execute("CREATE TABLE IF NOT EXISTS codex_house_points(house TINYINT PRIMARY KEY,points BIGINT NOT NULL DEFAULT 0) ENGINE=InnoDB");
                s.execute("CREATE TABLE IF NOT EXISTS codex_donations(spell VARCHAR(64) PRIMARY KEY,spell_name VARCHAR(100) NOT NULL,donor CHAR(36) NOT NULL,nickname VARCHAR(64) NOT NULL,house TINYINT NOT NULL,created BIGINT NOT NULL,KEY ix_donations_order(created,spell)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
                s.execute("CREATE TABLE IF NOT EXISTS codex_houses(player CHAR(36) PRIMARY KEY,house TINYINT NOT NULL) ENGINE=InnoDB");
            }else{
                s.execute("CREATE TABLE IF NOT EXISTS house_points(house INTEGER PRIMARY KEY,points INTEGER NOT NULL)");
                s.execute("CREATE TABLE IF NOT EXISTS donations(spell TEXT PRIMARY KEY,spell_name TEXT NOT NULL,donor TEXT NOT NULL,nickname TEXT NOT NULL,house INTEGER NOT NULL,created INTEGER NOT NULL)");
                s.execute("CREATE TABLE IF NOT EXISTS houses(player TEXT PRIMARY KEY,house INTEGER NOT NULL)");
            }
            // 관리자가 미리 정한 소속 (/유저관리 <닉네임> 기숙사 <기숙사>). 이 표에 있는 유저만 분류 거울의 창시자 연출(ch1-14f-)을 본다.
            s.execute("CREATE TABLE IF NOT EXISTS "+presets+"(player "+(maria?"CHAR(36)":"TEXT")+" PRIMARY KEY)"+(maria?" ENGINE=InnoDB":""));
            s.execute("CREATE TABLE IF NOT EXISTS codex_house_assignment_lock(id INT PRIMARY KEY,revision INT NOT NULL)"+(maria?" ENGINE=InnoDB":""));
            s.execute((maria?"INSERT IGNORE INTO ":"INSERT OR IGNORE INTO ")+"codex_house_assignment_lock VALUES(1,0)");
            s.execute("CREATE TABLE IF NOT EXISTS codex_quest_house_awards(token VARCHAR(36) PRIMARY KEY,house INT NOT NULL,points BIGINT NOT NULL)"+(maria?" ENGINE=InnoDB":""));
            for(int i=0;i<4;i++)s.execute((maria?"INSERT IGNORE INTO ":"INSERT OR IGNORE INTO ")+points+" VALUES("+i+",0)");
        }
    }
    Snapshot snapshot()throws SQLException{return snapshot(null);}
    Snapshot snapshot(Collection<UUID> activePlayers)throws SQLException{
        var scores=new ArrayList<Long>();var records=new ArrayList<Donation>();var houses=new HashMap<UUID,Integer>();
        try(var s=db().createStatement();var r=s.executeQuery("SELECT points FROM "+points+" ORDER BY house")){while(r.next())scores.add(r.getLong(1));}
        try(var s=db().createStatement();var r=s.executeQuery("SELECT * FROM "+donations+" ORDER BY created DESC,"+(maria?"spell":"rowid")+" DESC")){while(r.next())records.add(new Donation(r.getString("spell"),r.getString("spell_name"),UUID.fromString(r.getString("donor")),r.getString("nickname"),r.getInt("house"),r.getLong("created")));}
        if(activePlayers==null){try(var s=db().createStatement();var r=s.executeQuery("SELECT * FROM "+this.houses)){while(r.next())houses.put(UUID.fromString(r.getString(1)),r.getInt(2));}}
        else if(!activePlayers.isEmpty()){
            var ids=List.copyOf(activePlayers);
            for(int from=0;from<ids.size();from+=128){
                var batch=ids.subList(from,Math.min(from+128,ids.size()));
                String placeholders=String.join(",",Collections.nCopies(batch.size(),"?"));
                try(var s=db().prepareStatement("SELECT player,house FROM "+this.houses+" WHERE player IN ("+placeholders+")")){
                    for(int i=0;i<batch.size();i++)s.setString(i+1,batch.get(i).toString());
                    try(var r=s.executeQuery()){while(r.next())houses.put(UUID.fromString(r.getString(1)),r.getInt(2));}
                }
            }
        }
        var blocked=new HashSet<String>();try(var q=db().createStatement();var r=q.executeQuery("SELECT spell FROM "+policy+" WHERE blocked<>0")){while(r.next())blocked.add(r.getString(1));}
        return new Snapshot(scores,records,houses,blocked);
    }
    boolean donate(Donation e,long points)throws SQLException{
        if(e.house()<0||e.house()>3||points<0||points>MAX_SCORE)throw new IllegalArgumentException("Donation");
        holder.begin();
        try{
            if(lockPolicy(e.spell()))throw new DonationBlocked();
            try(var s=db().prepareStatement((maria?"INSERT IGNORE INTO ":"INSERT OR IGNORE INTO ")+donations+" VALUES(?,?,?,?,?,?)")){
                s.setString(1,e.spell());s.setString(2,e.spellName());s.setString(3,e.donor().toString());s.setString(4,e.nickname());s.setInt(5,e.house());s.setLong(6,e.time());
                if(s.executeUpdate()==0){holder.rollback();return false;}
            }
            change(e.house(),points,false);db().commit();return true;
        }catch(SQLException|RuntimeException ex){holder.rollback();throw ex;}finally{holder.end();}
    }
    /** Serialize block/unblock and donation on the same per-spell row. Must run inside a transaction. */
    private boolean lockPolicy(String spell)throws SQLException{
        if(!spell.matches("[a-z0-9_-]{1,64}"))throw new IllegalArgumentException("Spell ID");
        // MariaDB upsert takes an exclusive row lock immediately, avoiding concurrent INSERT IGNORE shared-lock upgrades.
        // The insert is also the first SQLite write, so concurrent connections cannot read a stale policy then upgrade a read transaction.
        try(var q=db().prepareStatement((maria?"INSERT INTO ":"INSERT OR IGNORE INTO ")+policy+"(spell,blocked) VALUES(?,0)"+(maria?" ON DUPLICATE KEY UPDATE spell=VALUES(spell)":""))){q.setString(1,spell);q.executeUpdate();}
        try(var q=db().prepareStatement("SELECT blocked FROM "+policy+" WHERE spell=?"+(maria?" FOR UPDATE":""))){q.setString(1,spell);try(var r=q.executeQuery()){if(!r.next())throw new SQLException("Missing policy row");return r.getInt(1)!=0;}}
    }
    void setBlocked(String spell,boolean blocked)throws SQLException{
        holder.begin();try{lockPolicy(spell);try(var q=db().prepareStatement("UPDATE "+policy+" SET blocked=? WHERE spell=?")){q.setInt(1,blocked?1:0);q.setString(2,spell);q.executeUpdate();}db().commit();}
        catch(SQLException|RuntimeException error){holder.rollback();throw error;}finally{holder.end();}
    }
    boolean questReward(String token,int house,long value)throws SQLException{
        UUID.fromString(token);if(house<0||house>3||value<1||value>MAX_SCORE)throw new IllegalArgumentException("Quest house reward");holder.begin();
        try{try(var s=db().prepareStatement((maria?"INSERT IGNORE INTO ":"INSERT OR IGNORE INTO ")+"codex_quest_house_awards(token,house,points) VALUES(?,?,?)")){s.setString(1,token);s.setInt(2,house);s.setLong(3,value);if(s.executeUpdate()==0){holder.rollback();return false;}}change(house,value,false);db().commit();return true;}catch(SQLException|RuntimeException ex){holder.rollback();throw ex;}finally{holder.end();}
    }
    void change(int house,long value,boolean set)throws SQLException{
        if(house<0||house>3||Math.abs(value)>MAX_SCORE)throw new IllegalArgumentException("Score range");
        if(maria){
            String sql=set?"UPDATE "+points+" SET points=? WHERE house=?":"UPDATE "+points+" SET points=points+? WHERE house=? AND points+? BETWEEN ? AND ?";
            try(var s=db().prepareStatement(sql)){s.setLong(1,value);s.setInt(2,house);if(!set){s.setLong(3,value);s.setLong(4,-MAX_SCORE);s.setLong(5,MAX_SCORE);}if(s.executeUpdate()==0&&!set)throw new IllegalArgumentException("Score range exceeded");}
            return;
        }
        long before;try(var s=db().prepareStatement("SELECT points FROM "+points+" WHERE house=?")){s.setInt(1,house);try(var r=s.executeQuery()){r.next();before=r.getLong(1);}}
        long next=set?value:Math.addExact(before,value);if(next < -MAX_SCORE||next>MAX_SCORE)throw new IllegalArgumentException("Score range exceeded");
        try(var s=db().prepareStatement("UPDATE "+points+" SET points=? WHERE house=?")){s.setLong(1,next);s.setInt(2,house);s.executeUpdate();}
    }
    record Assignment(int house,int preferred,boolean created) {}
    /** A shared DB row serializes all updated writers, including different Bukkit servers. */
    private void lockHouses()throws SQLException{
        try(var q=db().createStatement()){if(q.executeUpdate("UPDATE codex_house_assignment_lock SET revision=1-revision WHERE id=1")!=1)throw new SQLException("Missing house assignment lock");}
    }
    Integer savedHouse(UUID id)throws SQLException{
        try(var q=db().prepareStatement("SELECT house FROM "+houses+" WHERE player=?")){q.setString(1,id.toString());try(var r=q.executeQuery()){
            if(!r.next())return null;int h=r.getInt(1);if(h<0||h>3)throw new SQLException("Invalid saved house");return h;
        }}
    }
    long[] houseCounts()throws SQLException{
        long[] counts=new long[4]; // GROUP BY omits empty houses: keep explicit zeroes.
        try(var q=db().createStatement();var r=q.executeQuery("SELECT house,COUNT(*) FROM "+houses+" GROUP BY house")){
            while(r.next()){int h=r.getInt(1);if(h<0||h>3)throw new SQLException("Invalid saved house");counts[h]=r.getLong(2);}
        }return counts;
    }
    Assignment assignHouse(UUID id,int t,int d,int tolerance)throws SQLException{return assignHouse(id,t,d,tolerance,-1,0);}
    Assignment assignHouse(UUID id,int t,int d,int tolerance,int wish,int bonus)throws SQLException{
        int preferred=HouseSorting.preferred(t,d,wish,bonus);HouseSorting.tolerance(tolerance);
        holder.begin();try{
            lockHouses();Integer existing=savedHouse(id);
            if(existing!=null){db().commit();return new Assignment(existing,preferred,false);}
            int chosen=HouseSorting.choose(t,d,houseCounts(),tolerance,wish,bonus);
            writeHouse(id,chosen);db().commit();return new Assignment(chosen,preferred,true);
        }catch(SQLException|RuntimeException error){holder.rollback();throw error;}finally{holder.end();}
    }
    /** 분류 시작 판단용: DB 소속과 "관리자가 미리 정함" 표시. */
    record Start(Integer house,boolean preset) {}
    Start sortingStart(UUID id)throws SQLException{
        Integer h=savedHouse(id);if(h==null)return new Start(null,false);
        try(var q=db().prepareStatement("SELECT 1 FROM "+presets+" WHERE player=?")){q.setString(1,id.toString());try(var r=q.executeQuery()){return new Start(h,r.next());}}
    }
    /** 관리자가 미리 정함: 소속 저장과 미리 정함 표시를 한 트랜잭션에. */
    void presetHouse(UUID id,int house)throws SQLException{
        if(house<0||house>3)throw new IllegalArgumentException("House");
        holder.begin();try{lockHouses();writeHouse(id,house);
            try(var s=db().prepareStatement((maria?"INSERT IGNORE INTO ":"INSERT OR IGNORE INTO ")+presets+"(player) VALUES(?)")){s.setString(1,id.toString());s.executeUpdate();}
            db().commit();}
        catch(SQLException|RuntimeException error){holder.rollback();throw error;}finally{holder.end();}
    }
    /** 권한·스탯으로만 소속이 있는 유저: 그 소속을 DB 에 그대로 옮긴다 (다른 기숙사로 다시 분류하지 않음). 이미 DB 소속이 있으면 그것. */
    Assignment adoptHouse(UUID id,int house)throws SQLException{
        if(house<0||house>3)throw new IllegalArgumentException("House");
        holder.begin();try{
            lockHouses();Integer existing=savedHouse(id);
            if(existing!=null){db().commit();return new Assignment(existing,existing,false);}
            writeHouse(id,house);db().commit();return new Assignment(house,house,true);
        }catch(SQLException|RuntimeException error){holder.rollback();throw error;}finally{holder.end();}
    }
    /** 소속 초기화 (테스트 계정 등): DB 소속과 미리 정함 표시를 지운다. 지운 소속이 있으면 true. */
    boolean clearHouse(UUID id)throws SQLException{
        holder.begin();try{
            lockHouses();int n;
            try(var s=db().prepareStatement("DELETE FROM "+houses+" WHERE player=?")){s.setString(1,id.toString());n=s.executeUpdate();}
            try(var s=db().prepareStatement("DELETE FROM "+presets+" WHERE player=?")){s.setString(1,id.toString());s.executeUpdate();}
            db().commit();return n>0;
        }catch(SQLException|RuntimeException error){holder.rollback();throw error;}finally{holder.end();}
    }
    void house(UUID id,int house)throws SQLException{
        if(house<0||house>3)throw new IllegalArgumentException("House");
        holder.begin();try{lockHouses();writeHouse(id,house);db().commit();}
        catch(SQLException|RuntimeException error){holder.rollback();throw error;}finally{holder.end();}
    }
    private void writeHouse(UUID id,int house)throws SQLException{
        try(var s=db().prepareStatement(maria?"INSERT INTO "+houses+" VALUES(?,?) ON DUPLICATE KEY UPDATE house=VALUES(house)":"INSERT INTO "+houses+" VALUES(?,?) ON CONFLICT(player) DO UPDATE SET house=excluded.house")){s.setString(1,id.toString());s.setInt(2,house);s.executeUpdate();}
    }
    void reset(String spell)throws SQLException{
        if(spell.equals("all")){try(var s=db().createStatement()){s.executeUpdate("DELETE FROM "+donations);}}
        else try(var s=db().prepareStatement("DELETE FROM "+donations+" WHERE spell=?")){s.setString(1,spell);s.executeUpdate();}
    }
    void resetPoints()throws SQLException{try(var s=db().createStatement()){s.executeUpdate("UPDATE "+points+" SET points=0");}}
    public void close()throws SQLException{holder.close();}
}
