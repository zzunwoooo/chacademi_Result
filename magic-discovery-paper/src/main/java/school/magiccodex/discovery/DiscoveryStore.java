package school.magiccodex.discovery;
import java.sql.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import school.magiccodex.database.ConnectionHolder;
import school.magiccodex.database.DatabaseSettings;
/** All runtime disk I/O is serialized off the server thread; first discovery and acquisition share a transaction. */
final class DiscoveryStore implements AutoCloseable {
    record Acquisition(long token,String spell,boolean first,int reward,boolean notified){}
    /** learnedSignature: 다른 서버의 관리자 변경을 싸게 감지하기 위한 learned_overrides 요약값. */
    record Loaded(Map<String,Double> progress,List<Acquisition> acquired,Map<String,Boolean> learnedOverrides,Map<String,Map<String,Double>> retryProgress,String learnedSignature){}
    record Award(boolean created,Acquisition acquisition){}
    private final ExecutorService io=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"MagicDiscovery-storage");t.setDaemon(true);return t;});
    // 끊긴 연결(MariaDB 재시작·wait_timeout·소켓 타임아웃)은 다음 작업에서 다시 연다.
    private final ConnectionHolder holder;
    private Connection db()throws SQLException{return holder.get();}
    private final boolean maria;
    private final String progress,acquisitions,firsts,learnedOverrides,retryProgress,progressKey;
    DiscoveryStore(Path file)throws Exception{this(file,new DatabaseSettings(false,"","",""));}
    DiscoveryStore(Path file,DatabaseSettings settings)throws Exception{
        maria=settings.mariaDb();progress=settings.table("discovery_progress","progress");acquisitions=settings.table("discovery_acquisitions","acquisitions");firsts=settings.table("discovery_firsts","firsts");learnedOverrides=settings.table("discovery_learned_overrides","learned_overrides");retryProgress=settings.table("discovery_retry_progress","retry_progress");progressKey=maria?"progress_key":"key";
        holder=settings.holder(file);
        try(var s=db().createStatement()){
            if(maria){
                s.execute("CREATE TABLE IF NOT EXISTS "+learnedOverrides+"(player CHAR(36) NOT NULL,spell VARCHAR(64) NOT NULL,learned TINYINT NOT NULL,PRIMARY KEY(player,spell)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
                s.execute("CREATE TABLE IF NOT EXISTS "+retryProgress+"(player CHAR(36) NOT NULL,spell VARCHAR(64) NOT NULL,progress_key VARCHAR(128) NOT NULL,value DOUBLE NOT NULL,PRIMARY KEY(player,spell,progress_key)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
                s.execute("CREATE TABLE IF NOT EXISTS codex_discovery_progress(player CHAR(36) NOT NULL,progress_key VARCHAR(128) NOT NULL,value DOUBLE NOT NULL,PRIMARY KEY(player,progress_key)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
                s.execute("CREATE TABLE IF NOT EXISTS codex_discovery_acquisitions(token BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,player CHAR(36) NOT NULL,spell VARCHAR(64) NOT NULL,first_discovery TINYINT NOT NULL,reward TINYINT NOT NULL,notified TINYINT NOT NULL DEFAULT 0,UNIQUE KEY uq_acquisition(player,spell),KEY ix_acquisition_player(player,token)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
                s.execute("CREATE TABLE IF NOT EXISTS codex_discovery_firsts(spell VARCHAR(64) PRIMARY KEY,player CHAR(36) NOT NULL,created BIGINT NOT NULL) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            }else{
                s.execute("CREATE TABLE IF NOT EXISTS "+learnedOverrides+"(player TEXT NOT NULL,spell TEXT NOT NULL,learned INTEGER NOT NULL,PRIMARY KEY(player,spell))");
                s.execute("CREATE TABLE IF NOT EXISTS "+retryProgress+"(player TEXT NOT NULL,spell TEXT NOT NULL,progress_key TEXT NOT NULL,value REAL NOT NULL,PRIMARY KEY(player,spell,progress_key))");
                s.execute("CREATE TABLE IF NOT EXISTS progress(player TEXT,key TEXT,value REAL NOT NULL,PRIMARY KEY(player,key))");
                s.execute("CREATE TABLE IF NOT EXISTS acquisitions(token INTEGER PRIMARY KEY AUTOINCREMENT,player TEXT,spell TEXT,first INTEGER NOT NULL,reward INTEGER NOT NULL,notified INTEGER NOT NULL DEFAULT 0,UNIQUE(player,spell))");
                s.execute("CREATE TABLE IF NOT EXISTS firsts(spell TEXT PRIMARY KEY,player TEXT NOT NULL,created INTEGER NOT NULL)");
            }
        }catch(SQLException|RuntimeException e){io.shutdown();try{holder.close();}catch(SQLException ignored){}throw e;}
    }
    interface Work<T>{T run()throws Exception;}
    /** 한 트랜잭션. 연결은 begin()~end() 동안 고정되고, 깨진 연결은 end()에서 닫혀 다음 작업이 새로 연다. */
    private <T> T tx(Work<T> work)throws Exception{
        var c=holder.begin();
        try{T out=work.run();c.commit();return out;}
        catch(Exception e){try{holder.rollback();}catch(SQLException r){e.addSuppressed(r);}throw e;}
        finally{holder.end();}
    }
    private <T> CompletableFuture<T> task(Work<T> work){return CompletableFuture.supplyAsync(()->{try{return work.run();}catch(Exception e){throw new CompletionException(e);}},io);}
    CompletableFuture<Loaded> load(UUID id){return task(()->{
        // 요약값을 먼저 읽는다: 사이에 변경이 끼어들면 요약값이 더 오래된 쪽이 되어 다음 재확인에서 다시 읽힌다.
        String signature=signatures(List.of(id)).getOrDefault(id,"");
        Map<String,Double> values=new HashMap<>();try(var p=db().prepareStatement("SELECT "+progressKey+",value FROM "+progress+" WHERE player=?")){p.setString(1,id.toString());try(var r=p.executeQuery()){while(r.next())values.put(r.getString(1),r.getDouble(2));}}
        var acquired=new ArrayList<Acquisition>();try(var p=db().prepareStatement("SELECT token,spell,"+(maria?"first_discovery":"first")+",reward,notified FROM "+acquisitions+" WHERE player=? ORDER BY token")){p.setString(1,id.toString());try(var r=p.executeQuery()){while(r.next())acquired.add(row(r));}}
        Map<String,Boolean> overrides=new HashMap<>();try(var p=db().prepareStatement("SELECT spell,learned FROM "+learnedOverrides+" WHERE player=?")){p.setString(1,id.toString());try(var r=p.executeQuery()){while(r.next())overrides.put(r.getString(1),r.getBoolean(2));}}
        Map<String,Map<String,Double>> retries=new HashMap<>();try(var p=db().prepareStatement("SELECT spell,progress_key,value FROM "+retryProgress+" WHERE player=?")){p.setString(1,id.toString());try(var r=p.executeQuery()){while(r.next())retries.computeIfAbsent(r.getString(1),key->new HashMap<>()).put(r.getString(2),r.getDouble(3));}}
        return new Loaded(values,acquired,overrides,retries,signature);
    });}
    private Acquisition row(ResultSet r)throws SQLException{return new Acquisition(r.getLong(1),r.getString(2),r.getBoolean(3),r.getInt(4),r.getBoolean(5));}
    /** 누적 카운터는 단조 증가다. state.*(최신 상태)와 trace.*(되돌아가는 플래그)만 마지막 값으로 덮어쓴다. */
    static boolean monotonic(String key){return !key.startsWith("state.")&&!key.startsWith("trace.");}
    /** 카운터는 DB 값과 큰 쪽으로 병합한다: 서버 이동 직후 이전 서버의 마지막 저장을 낮은 값으로 덮어쓰지 않는다. */
    private void putProgress(UUID id,Map<String,Double> values)throws SQLException{
        if(values.isEmpty())return;
        String head=maria?"INSERT INTO "+progress+"(player,progress_key,value) VALUES(?,?,?) ON DUPLICATE KEY UPDATE value=":"INSERT INTO "+progress+" VALUES(?,?,?) ON CONFLICT(player,key) DO UPDATE SET value=";
        for(boolean merge:new boolean[]{true,false}){
            String sql=head+(merge?(maria?"GREATEST(value,VALUES(value))":"MAX(value,excluded.value)"):(maria?"VALUES(value)":"excluded.value"));
            try(var p=db().prepareStatement(sql)){int rows=0;for(var e:values.entrySet()){if(monotonic(e.getKey())!=merge)continue;p.setString(1,id.toString());p.setString(2,e.getKey());p.setDouble(3,e.getValue());p.addBatch();rows++;}if(rows>0)p.executeBatch();}
        }
    }
    private Map<UUID,String> signatures(Collection<UUID> ids)throws SQLException{
        Map<UUID,String> out=new HashMap<>();var all=List.copyOf(ids);
        String summary=maria?"CONCAT(COUNT(*),':',SUM(CRC32(CONCAT(spell,':',learned))))":"COUNT(*)||':'||GROUP_CONCAT(spell||':'||learned)";
        for(int from=0;from<all.size();from+=200){
            var part=all.subList(from,Math.min(all.size(),from+200));
            try(var p=db().prepareStatement("SELECT player,"+summary+" FROM "+learnedOverrides+" WHERE player IN("+String.join(",",Collections.nCopies(part.size(),"?"))+") GROUP BY player")){
                for(int i=0;i<part.size();i++)p.setString(i+1,part.get(i).toString());
                try(var r=p.executeQuery()){while(r.next()){String value=r.getString(2);out.put(UUID.fromString(r.getString(1)),value==null?"":value);}}
            }
        }
        return out;
    }
    /** 접속자 전체의 습득 상태 요약값을 한 번의 작은 조회로 읽는다. 행이 없는 플레이어는 결과에 없다. */
    CompletableFuture<Map<UUID,String>> learnedSignatures(Collection<UUID> ids){var copy=List.copyOf(ids);return task(()->signatures(copy));}
    private void putLearned(UUID id,String spell,boolean learned)throws SQLException{
        String sql="INSERT INTO "+learnedOverrides+"(player,spell,learned) VALUES(?,?,?) "+(maria?"ON DUPLICATE KEY UPDATE learned=VALUES(learned)":"ON CONFLICT(player,spell) DO UPDATE SET learned=excluded.learned");
        try(var p=db().prepareStatement(sql)){p.setString(1,id.toString());p.setString(2,spell);p.setBoolean(3,learned);p.executeUpdate();}
    }
    /** Writes no acquisition, server-first, notification or reward rows. */
    CompletableFuture<Map<String,Double>> setLearned(UUID id,String spell,boolean learned,Set<String> requirements,Map<String,Double> latestProgress){return task(()->tx(()->{
        putProgress(id,latestProgress);
        Map<String,Double> baseline=new HashMap<>();
        if(!learned)try(var p=db().prepareStatement("SELECT "+progressKey+",value FROM "+progress+" WHERE player=?")){p.setString(1,id.toString());try(var r=p.executeQuery()){while(r.next())if(requirements.contains(r.getString(1)))baseline.put(r.getString(1),r.getDouble(2));}}
        if(!learned)for(String key:requirements)baseline.putIfAbsent(key,0d);
        try(var p=db().prepareStatement("DELETE FROM "+retryProgress+" WHERE player=? AND spell=?")){p.setString(1,id.toString());p.setString(2,spell);p.executeUpdate();}
        try(var p=db().prepareStatement("INSERT INTO "+retryProgress+"(player,spell,progress_key,value) VALUES(?,?,?,?)")){for(var entry:baseline.entrySet()){p.setString(1,id.toString());p.setString(2,spell);p.setString(3,entry.getKey());p.setDouble(4,entry.getValue());p.addBatch();}if(!baseline.isEmpty())p.executeBatch();}
        putLearned(id,spell,learned);return Map.copyOf(baseline);
    }));}
    /** 넘겨받은 키만 한 트랜잭션·배치로 기록한다. 호출 쪽은 바뀐 키만 넘긴다. */
    CompletableFuture<Void> revokeCondition(UUID id,String spell,boolean reset){return task(()->tx(()->{
        putProgress(id,Map.of(Definitions.conditionKey(spell),0d));
        if(reset){putLearned(id,spell,false);try(var q=db().prepareStatement("DELETE FROM "+retryProgress+" WHERE player=? AND spell=?")){q.setString(1,id.toString());q.setString(2,spell);q.executeUpdate();}}
        return null;
    }));}
    /** 관리자 설정: 카운터도 큰 값 병합 없이 그 값으로 덮어쓴다. */
    CompletableFuture<Void> overwrite(UUID id,String key,double value){return task(()->tx(()->{
        String sql=maria?"INSERT INTO "+progress+"(player,progress_key,value) VALUES(?,?,?) ON DUPLICATE KEY UPDATE value=VALUES(value)":"INSERT INTO "+progress+" VALUES(?,?,?) ON CONFLICT(player,key) DO UPDATE SET value=excluded.value";
        try(var p=db().prepareStatement(sql)){p.setString(1,id.toString());p.setString(2,key);p.setDouble(3,value);p.executeUpdate();}
        return null;
    }));}
    CompletableFuture<Void> save(UUID id,Map<String,Double> values){return task(()->{if(!values.isEmpty())tx(()->{putProgress(id,values);return null;});return null;});}
    CompletableFuture<Award> acquire(UUID id,String spell,Map<String,Double> values,boolean everyPlayer){return task(()->tx(()->acquireNow(id,spell,values,everyPlayer)));}
    CompletableFuture<Award> grantCondition(UUID id,String spell,boolean everyPlayer){return task(()->tx(()->{
        var values=Map.of(Definitions.conditionKey(spell),1d);
        // An earlier administrator learned grant is not a new first discovery either.
        try(var q=db().prepareStatement("SELECT learned FROM "+learnedOverrides+" WHERE player=? AND spell=?")){q.setString(1,id.toString());q.setString(2,spell);try(var r=q.executeQuery()){if(r.next()&&r.getBoolean(1)){putProgress(id,values);return new Award(false,null);}}}
        return acquireNow(id,spell,values,everyPlayer);
    }));}
    private Award acquireNow(UUID id,String spell,Map<String,Double> values,boolean everyPlayer)throws Exception{
        Acquisition old=null;
        try(var q=db().prepareStatement("SELECT token,spell,"+(maria?"first_discovery":"first")+",reward,notified FROM "+acquisitions+" WHERE player=? AND spell=?")){q.setString(1,id.toString());q.setString(2,spell);try(var r=q.executeQuery()){if(r.next())old=row(r);}}
        if(old!=null){putProgress(id,values);putLearned(id,spell,true);return new Award(false,old);}
        boolean first;try(var q=db().prepareStatement((maria?"INSERT IGNORE INTO ":"INSERT OR IGNORE INTO ")+firsts+" VALUES(?,?,?)")){q.setString(1,spell);q.setString(2,id.toString());q.setLong(3,System.currentTimeMillis());first=q.executeUpdate()==1;}
        long token;try(var q=db().prepareStatement("INSERT INTO "+acquisitions+"(player,spell,"+(maria?"first_discovery":"first")+",reward) VALUES(?,?,?,?)",Statement.RETURN_GENERATED_KEYS)){q.setString(1,id.toString());q.setString(2,spell);q.setBoolean(3,first);q.setInt(4,first||everyPlayer?1:0);q.executeUpdate();try(var keys=q.getGeneratedKeys()){keys.next();token=keys.getLong(1);}}
        putProgress(id,values);putLearned(id,spell,true);return new Award(true,new Acquisition(token,spell,first,first||everyPlayer?1:0,false));
    }

    CompletableFuture<Void> acknowledge(UUID id,long token){return task(()->{try(var p=db().prepareStatement("UPDATE "+acquisitions+" SET notified=1 WHERE player=? AND token=?")){p.setString(1,id.toString());p.setLong(2,token);p.executeUpdate();}return null;});}
    CompletableFuture<Boolean> reserve(UUID id,long token){return task(()->{try(var p=db().prepareStatement("UPDATE "+acquisitions+" SET reward=2 WHERE player=? AND token=? AND reward=1")){p.setString(1,id.toString());p.setLong(2,token);return p.executeUpdate()==1;}});}
    CompletableFuture<Void> delivered(UUID id,long token){return task(()->{try(var p=db().prepareStatement("UPDATE "+acquisitions+" SET reward=3 WHERE player=? AND token=? AND reward=2")){p.setString(1,id.toString());p.setLong(2,token);p.executeUpdate();}return null;});}
    CompletableFuture<Void> unreserve(UUID id,long token){return task(()->{try(var p=db().prepareStatement("UPDATE "+acquisitions+" SET reward=1 WHERE player=? AND token=? AND reward=2")){p.setString(1,id.toString());p.setLong(2,token);p.executeUpdate();}return null;});}
    /** 관리자 복구용: 예약(2) 상태로 멈춘 보상 목록. */
    CompletableFuture<List<Acquisition>> reserved(UUID id){return task(()->{var out=new ArrayList<Acquisition>();try(var p=db().prepareStatement("SELECT token,spell,"+(maria?"first_discovery":"first")+",reward,notified FROM "+acquisitions+" WHERE player=? AND reward=2 ORDER BY token")){p.setString(1,id.toString());try(var r=p.executeQuery()){while(r.next())out.add(row(r));}}return List.copyOf(out);});}
    /** 관리자 복구용: 예약(2) 상태 보상을 수령 가능(1) 또는 지급 완료(3)로 바꾼다. 예약 상태가 아니면 false. */
    CompletableFuture<Boolean> resolveReserved(UUID id,long token,boolean claimable){return task(()->{try(var p=db().prepareStatement("UPDATE "+acquisitions+" SET reward=? WHERE player=? AND token=? AND reward=2")){p.setInt(1,claimable?1:3);p.setString(2,id.toString());p.setLong(3,token);return p.executeUpdate()==1;}});}
    @Override public void close()throws Exception{io.shutdown();if(!io.awaitTermination(20,TimeUnit.SECONDS))throw new IllegalStateException("Storage still flushing; do not close connection");holder.close();}
}
