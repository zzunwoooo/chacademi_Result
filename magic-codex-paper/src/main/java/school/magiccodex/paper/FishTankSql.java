package school.magiccodex.paper;

import java.sql.*;
import java.util.*;
import school.magiccodex.paper.FishTank.Fish;
import school.magiccodex.paper.FishTank.Put;

/** Durable per-fish state; SQL transactions serialize capacity, locks and state transitions across servers. */
final class FishTankSql {
    enum Action { ITEMIZE, SELL, RELEASE }
    private final Connection c;
    private final boolean maria;
    FishTankSql(Connection connection, boolean maria) { c=connection;this.maria=maria; }
    void init() throws SQLException {
        String engine=maria?" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE utf8mb4_unicode_ci":"";
        try(var s=c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS codex_fish_tank(id VARCHAR(36) PRIMARY KEY,uuid VARCHAR(36) NOT NULL,fish VARCHAR(64) NOT NULL,size DOUBLE NOT NULL,caught_at BIGINT NOT NULL,fish_state VARCHAR(16) NOT NULL DEFAULT 'TANK',item_token VARCHAR(36) NOT NULL DEFAULT '')"+engine);
            s.execute("CREATE TABLE IF NOT EXISTS codex_fish_lock(uuid VARCHAR(36) NOT NULL,fish VARCHAR(64) NOT NULL,PRIMARY KEY(uuid,fish))"+engine);
            s.execute("CREATE TABLE IF NOT EXISTS codex_fish_mutex(uuid VARCHAR(36) PRIMARY KEY)"+engine);
        }
        column("fish_state","VARCHAR(16) NOT NULL DEFAULT 'TANK'");column("item_token","VARCHAR(36) NOT NULL DEFAULT ''");
        try(var statement=c.createStatement()){statement.execute("CREATE INDEX IF NOT EXISTS codex_fish_tank_owner_state ON codex_fish_tank(uuid,fish_state)");}
    }
    private void column(String name,String type)throws SQLException {
        try(var s=c.createStatement();var r=s.executeQuery("SELECT "+name+" FROM codex_fish_tank WHERE 1=0")){return;}
        catch(SQLException missing){try(var s=c.createStatement()){s.execute("ALTER TABLE codex_fish_tank ADD COLUMN "+name+" "+type);}}
    }
    interface Work<T> {T run()throws SQLException;}
    private <T>T tx(UUID owner,Work<T> work)throws SQLException {
        boolean auto=c.getAutoCommit();c.setAutoCommit(false);
        try {
            try(var p=c.prepareStatement((maria?"INSERT INTO codex_fish_mutex(uuid) VALUES(?) ON DUPLICATE KEY UPDATE uuid=VALUES(uuid)":"INSERT OR IGNORE INTO codex_fish_mutex(uuid) VALUES(?)"))){p.setString(1,owner.toString());p.executeUpdate();}
            try(var p=c.prepareStatement("SELECT uuid FROM codex_fish_mutex WHERE uuid=?"+(maria?" FOR UPDATE":""))){p.setString(1,owner.toString());try(var r=p.executeQuery()){if(!r.next())throw new SQLException("Missing owner mutex");}}
            T out=work.run();c.commit();return out;
        }catch(SQLException|RuntimeException e){try{c.rollback();}catch(SQLException suppressed){e.addSuppressed(suppressed);}throw e;}
        finally{c.setAutoCommit(auto);}
    }
    List<Fish> list(UUID owner)throws SQLException {
        var out=new ArrayList<Fish>();
        try(var p=c.prepareStatement("SELECT id,fish,size,caught_at,item_token FROM codex_fish_tank WHERE uuid=? AND fish_state='TANK' ORDER BY caught_at,id")){
            p.setString(1,owner.toString());try(var r=p.executeQuery()){while(r.next())out.add(row(r));}
        }return out;
    }
    private Fish row(ResultSet r)throws SQLException{return new Fish(r.getString(1),r.getString(2),r.getDouble(3),r.getLong(4),r.getString(5));}
    Map<String,Fish> items(Collection<String> ids)throws SQLException {
        var out=new HashMap<String,Fish>();
        try(var p=c.prepareStatement("SELECT id,fish,size,caught_at,item_token FROM codex_fish_tank WHERE id=? AND fish_state='ITEM'")){
            for(String id:new HashSet<>(ids)){p.setString(1,id);try(var r=p.executeQuery()){if(r.next())out.put(id,row(r));}}
        }return Map.copyOf(out);
    }
    boolean registerItem(Fish f)throws SQLException {
        if(!Double.isFinite(f.size())||f.size()<=0||f.caughtAt()<=0||!f.id().matches("[a-fA-F0-9-]{36}")||f.token().isEmpty())return false;
        return tx(new UUID(0,0),()->{
            try(var p=c.prepareStatement("INSERT INTO codex_fish_tank(id,uuid,fish,size,caught_at,fish_state,item_token) VALUES(?,?,?,?,?,'ITEM',?)")){
                p.setString(1,f.id());p.setString(2,new UUID(0,0).toString());p.setString(3,f.fish());p.setDouble(4,f.size());p.setLong(5,f.caughtAt());p.setString(6,f.token());return p.executeUpdate()==1;
            }
        });
    }
    Set<String> locks(UUID owner)throws SQLException {
        var out=new HashSet<String>();try(var p=c.prepareStatement("SELECT fish FROM codex_fish_lock WHERE uuid=?")){p.setString(1,owner.toString());try(var r=p.executeQuery()){while(r.next())out.add(r.getString(1));}}return out;
    }
    void locks(UUID owner,Set<String> species)throws SQLException {
        tx(owner,()->{try(var d=c.prepareStatement("DELETE FROM codex_fish_lock WHERE uuid=?");var p=c.prepareStatement("INSERT INTO codex_fish_lock(uuid,fish) VALUES(?,?)")){
            d.setString(1,owner.toString());d.executeUpdate();for(String id:species){p.setString(1,owner.toString());p.setString(2,id);p.executeUpdate();}
        }return null;});
    }
    Put put(UUID owner,Fish fish,int capacity)throws SQLException {
        if(!Double.isFinite(fish.size())||fish.size()<=0||fish.caughtAt()<=0||!fish.id().matches("[a-fA-F0-9-]{36}"))return Put.FAILED;
        return tx(owner,()->{
            String state=null,token=null;Fish existing=null;
            try(var p=c.prepareStatement("SELECT id,fish,size,caught_at,item_token,fish_state FROM codex_fish_tank WHERE id=?"+(maria?" FOR UPDATE":""))){p.setString(1,fish.id());try(var r=p.executeQuery()){if(r.next()){existing=row(r);token=r.getString(5);state=r.getString(6);}}}
            if(state!=null&&(!state.equals("ITEM")||!Objects.equals(token,fish.token())||!existing.fish().equals(fish.fish())||Math.abs(existing.size()-fish.size())>.051||existing.caughtAt()!=fish.caughtAt()))return Put.DUPLICATE;
            try(var p=c.prepareStatement("SELECT COUNT(*) FROM codex_fish_tank WHERE uuid=? AND fish_state IN ('TANK','TAKING','SELLING')")){p.setString(1,owner.toString());try(var r=p.executeQuery()){if(r.next()&&r.getInt(1)>=capacity)return Put.FULL;}}
            if(state==null){try(var p=c.prepareStatement("INSERT INTO codex_fish_tank(id,uuid,fish,size,caught_at,fish_state,item_token) VALUES(?,?,?,?,?,'TANK',?)")){p.setString(1,fish.id());p.setString(2,owner.toString());p.setString(3,fish.fish());p.setDouble(4,fish.size());p.setLong(5,fish.caughtAt());p.setString(6,fish.token());p.executeUpdate();}}
            else try(var p=c.prepareStatement("UPDATE codex_fish_tank SET uuid=?,fish_state='TANK' WHERE id=? AND fish_state='ITEM' AND item_token=?")){p.setString(1,owner.toString());p.setString(2,fish.id());p.setString(3,fish.token());if(p.executeUpdate()!=1)return Put.DUPLICATE;}
            return Put.OK;
        });
    }
    List<Fish> claim(UUID owner,List<Fish> wanted,Action action)throws SQLException {
        if(wanted.isEmpty())return List.of();
        return tx(owner,()->{
            Set<String> locked=action==Action.SELL?locks(owner):Set.of();var found=new ArrayList<Fish>();var ids=new HashSet<String>();
            for(Fish f:wanted){
                if(!ids.add(f.id()))return List.of();
                try(var p=c.prepareStatement("SELECT id,fish,size,caught_at,item_token FROM codex_fish_tank WHERE id=? AND uuid=? AND fish_state='TANK'"+(maria?" FOR UPDATE":""))){p.setString(1,f.id());p.setString(2,owner.toString());try(var r=p.executeQuery()){if(!r.next())return List.of();Fish actual=row(r);if(locked.contains(actual.fish())||!actual.fish().equals(f.fish())||actual.size()!=f.size()||actual.caughtAt()!=f.caughtAt())return List.of();found.add(actual);}}
            }
            var out=new ArrayList<Fish>();String next=switch(action){case ITEMIZE->"TAKING";case SELL->"SELLING";case RELEASE->"RELEASED";};
            try(var p=c.prepareStatement("UPDATE codex_fish_tank SET fish_state=?,item_token=? WHERE id=? AND uuid=? AND fish_state='TANK'")){
                for(Fish f:found){String token=action==Action.ITEMIZE?UUID.randomUUID().toString():f.token();p.setString(1,next);p.setString(2,token);p.setString(3,f.id());p.setString(4,owner.toString());if(p.executeUpdate()!=1)throw new SQLException("Concurrent fish transition");out.add(new Fish(f.id(),f.fish(),f.size(),f.caughtAt(),token));}
            }return List.copyOf(out);
        });
    }
    /** 판매(SELLING)·꺼내기(TAKING) 도중 멈춘 물고기. 수조 용량을 계속 차지하므로 관리자가 보고 정리한다 (2026-10-21). */
    record Stuck(Fish fish,Action action){}
    List<Stuck> stuck(UUID owner)throws SQLException {
        var out=new ArrayList<Stuck>();
        try(var p=c.prepareStatement("SELECT id,fish,size,caught_at,item_token,fish_state FROM codex_fish_tank WHERE uuid=? AND fish_state IN ('SELLING','TAKING') ORDER BY caught_at,id")){
            p.setString(1,owner.toString());try(var r=p.executeQuery()){while(r.next())out.add(new Stuck(row(r),r.getString(6).equals("SELLING")?Action.SELL:Action.ITEMIZE));}
        }return List.copyOf(out);
    }
    /** 서버 전체에서 멈춘 판매·꺼내기 수 (시작 시 콘솔 경고용). */
    int stuckCount()throws SQLException {
        try(var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM codex_fish_tank WHERE fish_state IN ('SELLING','TAKING')")){return r.next()?r.getInt(1):0;}
    }
    boolean finish(UUID owner,List<Fish> fish,Action action,boolean success)throws SQLException {
        return tx(owner,()->{
            String before=action==Action.ITEMIZE?"TAKING":"SELLING",after=success?(action==Action.ITEMIZE?"ITEM":"SOLD"):"TANK";
            try(var p=c.prepareStatement("UPDATE codex_fish_tank SET fish_state=? WHERE id=? AND uuid=? AND fish_state=? AND item_token=?")){
                for(Fish f:fish){p.setString(1,after);p.setString(2,f.id());p.setString(3,owner.toString());p.setString(4,before);p.setString(5,f.token());if(p.executeUpdate()!=1)throw new SQLException("Fish finalization does not own claim");}
            }return true;
        });
    }
}