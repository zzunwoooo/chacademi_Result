package school.magiccodex.paper;

import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import school.magiccodex.database.ConnectionHolder;
import school.magiccodex.database.DatabaseSettings;

/** Worker-owned connection, durable row locks and revision checks across backend servers. */
final class TitleStore implements AutoCloseable {
    record State(long revision,String prefix,String suffix,Set<String> owned){State{owned=Set.copyOf(owned);}}
    record Result(boolean applied,State state){}
    private final ConnectionHolder holder;private final boolean maria;
    private Connection db()throws SQLException{return holder.get();}
    TitleStore(DatabaseSettings settings,Path path)throws Exception{
        maria=settings.mariaDb();holder=settings.holder(path);
        try(var s=db().createStatement()){String tail=maria?" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4":"";
            s.execute("CREATE TABLE IF NOT EXISTS codex_title_player(player VARCHAR(36) PRIMARY KEY,revision BIGINT NOT NULL,prefix_id VARCHAR(48) NOT NULL,suffix_id VARCHAR(48) NOT NULL)"+tail);
            s.execute("CREATE TABLE IF NOT EXISTS codex_title_owned(player VARCHAR(36) NOT NULL,title_id VARCHAR(48) NOT NULL,PRIMARY KEY(player,title_id))"+tail);
            // 획득 경로 장부 (2026-10-25): 같은 획득 경로(마법 발견·호감도·낚시·사전 설정)는 학교·야생 어디서든 한 번만. 회수 뒤에도 다시 주지 않는다.
            s.execute("CREATE TABLE IF NOT EXISTS codex_title_awards(player VARCHAR(36) NOT NULL,award_key VARCHAR(191) NOT NULL,PRIMARY KEY(player,award_key))"+tail);
        }
    }
    record Awarded(List<String> granted,State state){Awarded{granted=List.copyOf(granted);}}
    private boolean insertLedger(UUID player,String key)throws SQLException{try(var s=db().prepareStatement("INSERT "+(maria?"IGNORE":"OR IGNORE")+" INTO codex_title_awards(player,award_key) VALUES(?,?)")){s.setString(1,player.toString());s.setString(2,key);return s.executeUpdate()>0;}}
    /** 장부 키 → 칭호 ID. 장부에 처음 적힌 키의 칭호만 보유 목록에 넣는다. granted = 이번에 새로 보유하게 된 칭호. */
    Awarded award(UUID p,Map<String,String> awards,Set<String> defaults)throws Exception{return transaction(p,defaults,()->{
        var granted=new ArrayList<String>();
        for(var e:awards.entrySet())if(insertLedger(p,e.getKey())&&insertOwned(p,e.getValue())&&!granted.contains(e.getValue()))granted.add(e.getValue());
        if(!granted.isEmpty())bump(p);return new Awarded(granted,read(p));
    });}
    /** 아직 장부에 없는 키 (사전 설정 기숙사·마법처럼 다른 저장소에 적용한 뒤 mark 하는 것). */
    Set<String> unmarked(UUID p,Collection<String> keys)throws SQLException{
        var out=new LinkedHashSet<String>(keys);if(out.isEmpty())return out;
        try(var s=db().prepareStatement("SELECT award_key FROM codex_title_awards WHERE player=?")){s.setString(1,p.toString());try(var r=s.executeQuery()){while(r.next())out.remove(r.getString(1));}}
        return out;
    }
    void mark(UUID p,String key)throws SQLException{insertLedger(p,key);}
    private interface Tx<T>{T run()throws Exception;}
    private <T>T transaction(UUID player,Set<String> defaults,Tx<T> action)throws Exception{
        for(int attempt=0;;attempt++){holder.begin();try{
            int created;String insert="INSERT "+(maria?"IGNORE":"OR IGNORE")+" INTO codex_title_player(player,revision,prefix_id,suffix_id) VALUES(?,0,'','')";
            try(var s=db().prepareStatement(insert)){s.setString(1,player.toString());created=s.executeUpdate();}
            // Lock after the insert; deadlock retries cover simultaneous first joins.
            try(var s=db().prepareStatement("UPDATE codex_title_player SET revision=revision WHERE player=?")){s.setString(1,player.toString());s.executeUpdate();}
            if(created>0)for(String id:defaults)insertOwned(player,id);
            T value=action.run();db().commit();return value;
        }catch(Exception e){holder.rollback();if(attempt<3&&e instanceof SQLException s&&(s.getErrorCode()==1213||s.getErrorCode()==1205||"40001".equals(s.getSQLState()))){Thread.sleep(10L*(attempt+1));continue;}throw e;}finally{holder.end();}}
    }
    private boolean insertOwned(UUID player,String id)throws SQLException{try(var s=db().prepareStatement("INSERT "+(maria?"IGNORE":"OR IGNORE")+" INTO codex_title_owned(player,title_id) VALUES(?,?)")){s.setString(1,player.toString());s.setString(2,id);return s.executeUpdate()>0;}}
    private void bump(UUID p)throws SQLException{try(var s=db().prepareStatement("UPDATE codex_title_player SET revision=revision+1 WHERE player=?")){s.setString(1,p.toString());s.executeUpdate();}}
    private State read(UUID player)throws SQLException{
        long revision;String prefix,suffix;try(var s=db().prepareStatement("SELECT revision,prefix_id,suffix_id FROM codex_title_player WHERE player=?")){s.setString(1,player.toString());try(var r=s.executeQuery()){if(!r.next())throw new SQLException("Missing title profile");revision=r.getLong(1);prefix=r.getString(2);suffix=r.getString(3);}}
        var owned=new HashSet<String>();try(var s=db().prepareStatement("SELECT title_id FROM codex_title_owned WHERE player=?")){s.setString(1,player.toString());try(var r=s.executeQuery()){while(r.next())owned.add(r.getString(1));}}return new State(revision,prefix,suffix,owned);
    }
    State load(UUID player,Set<String> defaults)throws Exception{return transaction(player,defaults,()->read(player));}
    State grant(UUID p,String id,Set<String> defaults)throws Exception{return transaction(p,defaults,()->{if(insertOwned(p,id))bump(p);return read(p);});}
    State revoke(UUID p,String id,Set<String> defaults)throws Exception{return transaction(p,defaults,()->{
        try(var s=db().prepareStatement("DELETE FROM codex_title_owned WHERE player=? AND title_id=?")){s.setString(1,p.toString());s.setString(2,id);if(s.executeUpdate()>0){try(var u=db().prepareStatement("UPDATE codex_title_player SET prefix_id=CASE WHEN prefix_id=? THEN '' ELSE prefix_id END,suffix_id=CASE WHEN suffix_id=? THEN '' ELSE suffix_id END WHERE player=?")){u.setString(1,id);u.setString(2,id);u.setString(3,p.toString());u.executeUpdate();}bump(p);}}return read(p);
    });}
    Result select(UUID p,long expected,String prefix,String suffix,Map<String,TitleDefinition> catalog,Set<String> defaults)throws Exception{return transaction(p,defaults,()->{
        var state=read(p);if(state.revision()!=expected||!eligible(prefix,0,state,catalog)||!eligible(suffix,1,state,catalog))return new Result(false,state);
        if(!state.prefix().equals(prefix)||!state.suffix().equals(suffix)){try(var s=db().prepareStatement("UPDATE codex_title_player SET prefix_id=?,suffix_id=?,revision=revision+1 WHERE player=?")){s.setString(1,prefix);s.setString(2,suffix);s.setString(3,p.toString());s.executeUpdate();}}
        return new Result(true,read(p));
    });}
    private static boolean eligible(String id,int side,State state,Map<String,TitleDefinition> catalog){if(id.isEmpty())return true;var d=catalog.get(id);return d!=null&&d.enabled()&&d.side()==side&&state.owned().contains(id);}
    @Override public void close()throws SQLException{holder.close();}
}
