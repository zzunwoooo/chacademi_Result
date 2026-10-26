package school.magiccodex.paper;

import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import school.magiccodex.database.ConnectionHolder;
import school.magiccodex.protocol.QuestAdminProtocol;
import school.magiccodex.database.DatabaseSettings;

/** One worker owns this connection. Transactions lock a UUID row across all backend servers. */
final class QuestStore implements AutoCloseable {
    /** 보상 지급 단계 비트 (codex_quest_progress.stage). RECORDED 가 없으면 "지급 도중 서버가 멈춰 어디까지 줬는지 기록하지 못함"이다. */
    static final int ITEMS=1,MONEY=2,COMMANDS=4,HOUSE=8,RECORDED=16;
    /** 모아 둔 진행도 한 건. */
    record Delta(QuestDefinition.Type type,String target,String world,int amount){}
    record Entry(QuestDefinition quest,String cycle,String status,int[] progress,String token,int completions,int stage) {
        Entry(QuestDefinition quest,String cycle,String status,int[] progress,String token,int completions){this(quest,cycle,status,progress,token,completions,0);}
        boolean current(long now){return cycle.equals(quest.cycle(now));}
        boolean complete(){for(int i=0;i<progress.length;i++)if(quest.objectives().get(i).type()!=QuestDefinition.Type.SUBMIT&&progress[i]<quest.objectives().get(i).amount())return false;return true;}
    }
    private final ConnectionHolder holder;private final boolean maria;
    private Connection db()throws SQLException{return holder.get();}
    QuestStore(DatabaseSettings settings,Path path)throws Exception{
        maria=settings.mariaDb();holder=settings.holder(path);
        try(var s=db().createStatement()){
            String tail=maria?" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4":"";
            s.execute("CREATE TABLE IF NOT EXISTS codex_quest_lock(player VARCHAR(36) PRIMARY KEY,version BIGINT NOT NULL)"+tail);
            s.execute("CREATE TABLE IF NOT EXISTS codex_quest_catalog(id VARCHAR(48) PRIMARY KEY,definition TEXT NOT NULL)"+tail);
            s.execute("CREATE TABLE IF NOT EXISTS codex_quest_progress(player VARCHAR(36) NOT NULL,quest VARCHAR(48) NOT NULL,cycle VARCHAR(16) NOT NULL,definition TEXT NOT NULL,status VARCHAR(16) NOT NULL,progress VARCHAR(128) NOT NULL,token VARCHAR(36) NOT NULL,updated BIGINT NOT NULL,PRIMARY KEY(player,quest,cycle))"+tail);
        }
        boolean column=false;try(var r=db().getMetaData().getColumns(null,null,"codex_quest_progress","completions")){column=r.next();}
        if(!column){
            try(var s=db().createStatement()){s.execute("ALTER TABLE codex_quest_progress ADD COLUMN completions INT NOT NULL DEFAULT 0");}catch(SQLException ex){try(var r=db().getMetaData().getColumns(null,null,"codex_quest_progress","completions")){if(!r.next())throw ex;}}
            // 열을 방금 만든 경우에만 필요한 1회성 이전이다. (예전에는 부팅마다 전체 테이블을 갱신했다. 지금 코드는 claimed 로 바꿀 때 항상 completions 를 올린다.)
            try(var s=db().createStatement()){s.executeUpdate("UPDATE codex_quest_progress SET completions=1 WHERE status='claimed' AND completions=0");}
        }
        // 추가 전용: 보상 지급 단계 기록. 이미 있으면 건너뛴다.
        boolean stage=true;try(var s=db().createStatement();var r=s.executeQuery("SELECT stage FROM codex_quest_progress WHERE 1=0")){r.next();}catch(SQLException ex){stage=false;}
        if(!stage)try(var s=db().createStatement()){s.execute("ALTER TABLE codex_quest_progress ADD COLUMN stage INT NOT NULL DEFAULT 0");}catch(SQLException ex){try(var s=db().createStatement();var r=s.executeQuery("SELECT stage FROM codex_quest_progress WHERE 1=0")){r.next();}catch(SQLException again){throw ex;}}
    }
    Map<String,QuestDefinition> catalog()throws SQLException{var map=new TreeMap<String,QuestDefinition>();try(var s=db().createStatement();var r=s.executeQuery("SELECT definition FROM codex_quest_catalog")){while(r.next()){var q=QuestDefinition.decode(r.getString(1));map.put(q.id(),q);}}return Map.copyOf(map);}
    /** 카탈로그를 바꾸는 모든 쓰기(publish/seed/edit/release)는 "catalog" 잠금 행의 version 을 올린다. 이 값만 읽어 바뀌었는지 싸게 확인한다. */
    long catalogRevision()throws SQLException{try(var s=db().createStatement();var r=s.executeQuery("SELECT version FROM codex_quest_lock WHERE player='catalog'")){return r.next()?r.getLong(1):0;}}
    void publish(Map<String,QuestDefinition> definitions)throws Exception{
        if(definitions.size()>QuestAdminProtocol.MAX_ENTRIES)throw new IllegalArgumentException("최대 "+QuestAdminProtocol.MAX_ENTRIES+"개 의뢰");
        transaction("catalog",()->{try(var s=db().createStatement()){s.executeUpdate("DELETE FROM codex_quest_catalog");}try(var s=db().prepareStatement("INSERT INTO codex_quest_catalog(id,definition) VALUES(?,?)")){for(var q:definitions.values()){s.setString(1,q.id());s.setString(2,q.encode());s.addBatch();}s.executeBatch();}return null;});
    }
    void seed(Map<String,QuestDefinition> definitions)throws Exception{transaction("catalog",()->{if(catalog().isEmpty()){try(var s=db().prepareStatement("INSERT INTO codex_quest_catalog(id,definition) VALUES(?,?)")){for(var q:definitions.values()){s.setString(1,q.id());s.setString(2,q.encode());s.addBatch();}s.executeBatch();}}return null;});}
    List<Entry> entries(UUID player)throws SQLException{return entries(player,System.currentTimeMillis());}
    private List<Entry> entries(UUID player,long now)throws SQLException{var out=new ArrayList<Entry>();try(var s=db().prepareStatement("SELECT definition,cycle,status,progress,token,completions,stage FROM codex_quest_progress WHERE player=? AND (cycle='once' OR cycle=? OR status='paying')")){s.setString(1,player.toString());s.setString(2,java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.of("Asia/Seoul")).toLocalDate().toString());try(var r=s.executeQuery()){while(r.next()){var q=QuestDefinition.decode(r.getString(1));int[] p=Arrays.stream(r.getString(4).split(",")).mapToInt(Integer::parseInt).toArray();if(p.length!=q.objectives().size())throw new SQLException("Quest progress mismatch");out.add(new Entry(q,r.getString(2),r.getString(3),p,r.getString(5),r.getInt(6),r.getInt(7)));}}}return out;}
    Set<String> completed(UUID player)throws SQLException{
        var result=new HashSet<String>();
        try(var s=db().prepareStatement("SELECT DISTINCT quest FROM codex_quest_progress WHERE player=? AND completions>0")){
            s.setString(1,player.toString());try(var rows=s.executeQuery()){while(rows.next())result.add(rows.getString(1));}
        }
        return Set.copyOf(result);
    }
    static boolean unlocked(QuestDefinition q,Set<String> completed){return completed.containsAll(q.requires());}
    static int activeSubquests(List<Entry> entries,long now){return (int)entries.stream().filter(e->!e.quest().main()&&(e.status().equals("paying")||(e.current(now)&&e.status().equals("active")))).count();}
    boolean accept(UUID player,QuestDefinition q,long now)throws Exception{return transaction(player.toString(),()->{
        if(!q.available(now)||!unlocked(q,completed(player)))return false;
        var all=entries(player,now);var previous=all.stream().filter(e->e.quest.id().equals(q.id())&&e.cycle.equals(q.cycle(now))).findFirst().orElse(null);
        if(all.stream().anyMatch(e->e.quest.id().equals(q.id())&&(e.status.equals("paying")||(e.current(now)&&e.status.equals("active"))))||(!q.main()&&activeSubquests(all,now)>=3))return false;
        int completed=previous==null?0:previous.completions;if(completed>=q.completionLimit())return false;
        if(previous!=null)try(var s=db().prepareStatement("DELETE FROM codex_quest_progress WHERE player=? AND quest=? AND cycle=?")){s.setString(1,player.toString());s.setString(2,q.id());s.setString(3,q.cycle(now));s.executeUpdate();}
        try(var s=db().prepareStatement("INSERT INTO codex_quest_progress(player,quest,cycle,definition,status,progress,token,updated,completions) VALUES(?,?,?,?,?,?,?,?,?)")){s.setString(1,player.toString());s.setString(2,q.id());s.setString(3,q.cycle(now));s.setString(4,q.encode());s.setString(5,"active");s.setString(6,counts(new int[q.objectives().size()]));s.setString(7,"");s.setLong(8,now);s.setInt(9,completed);s.executeUpdate();}return true;
    });}
    void advance(UUID player,QuestDefinition.Type type,String target,String server,String world,int amount,long now)throws Exception{advanceAll(player,List.of(new Delta(type,target,world,amount)),server,now);}
    /** 모아 둔 진행도 여러 건을 한 트랜잭션으로 반영한다. 목표 수치를 넘지 않게 자르고, 바뀐 의뢰만 한 번씩 기록한다. */
    void advanceAll(UUID player,List<Delta> deltas,String server,long now)throws Exception{if(deltas.isEmpty())return;transaction(player.toString(),()->{for(var e:entries(player)){if(!e.status.equals("active")||!e.current(now))continue;boolean dirty=false;for(var d:deltas){if(d.type()==QuestDefinition.Type.SUBMIT||d.amount()<1)continue;for(int i=0;i<e.progress.length;i++){var g=e.quest.objectives().get(i);if(g.matches(d.type(),d.target(),server,d.world())){int v=(int)Math.min(g.amount(),(long)e.progress[i]+d.amount());if(v!=e.progress[i]){e.progress[i]=v;dirty=true;}}}}if(dirty)try(var s=db().prepareStatement("UPDATE codex_quest_progress SET progress=?,updated=? WHERE player=? AND quest=? AND cycle=? AND status='active'")){s.setString(1,counts(e.progress));s.setLong(2,now);s.setString(3,player.toString());s.setString(4,e.quest.id());s.setString(5,e.cycle);s.executeUpdate();}}return null;});}
    Entry reserve(UUID player,String id,String expectedCycle,long now)throws Exception{return transaction(player.toString(),()->{for(var e:entries(player))if(e.quest.id().equals(id)&&e.cycle.equals(expectedCycle)&&e.current(now)&&e.status.equals("active")&&e.completions<e.quest.completionLimit()&&e.complete()){String token=UUID.randomUUID().toString();try(var s=db().prepareStatement("UPDATE codex_quest_progress SET status='paying',token=?,updated=?,stage=0 WHERE player=? AND quest=? AND cycle=? AND status='active'")){s.setString(1,token);s.setLong(2,now);s.setString(3,player.toString());s.setString(4,id);s.setString(5,e.cycle);if(s.executeUpdate()==1)return new Entry(e.quest,e.cycle,"paying",e.progress,token,e.completions,0);}}return null;});}
    /** 지급이 중간에 멈췄을 때 어디까지 줬는지 기록한다 (paying 유지). 관리자 retry 는 이 기록에 없는 단계만 실행한다. */
    void stage(UUID player,Entry e,int stage)throws SQLException{try(var s=db().prepareStatement("UPDATE codex_quest_progress SET stage=?,updated=? WHERE player=? AND quest=? AND cycle=? AND status='paying' AND token=?")){s.setInt(1,stage);s.setLong(2,System.currentTimeMillis());s.setString(3,player.toString());s.setString(4,e.quest.id());s.setString(5,e.cycle);s.setString(6,e.token);if(s.executeUpdate()!=1)throw new SQLException("Claim reservation changed");}}
    /** 검토 대기(paying) 기록 한 건. 없으면 null. */
    Entry paying(UUID player,String id,String cycle)throws SQLException{for(var e:entries(player))if(e.quest.id().equals(id)&&e.cycle.equals(cycle)&&e.status.equals("paying"))return e;return null;}
    void finish(UUID player,Entry e,boolean delivered)throws Exception{
        if(!holder.inTransaction())transaction(player.toString(),()->{finishLocked(player,e,delivered);return null;});else finishLocked(player,e,delivered);
    }
    private void finishLocked(UUID player,Entry e,boolean delivered)throws SQLException{
        try(var s=db().prepareStatement("UPDATE codex_quest_progress SET status=?,token='',stage=0,updated=?,completions=completions+? WHERE player=? AND quest=? AND cycle=? AND status='paying' AND token=?")){s.setString(1,delivered?"claimed":"active");s.setLong(2,System.currentTimeMillis());s.setInt(3,delivered?1:0);s.setString(4,player.toString());s.setString(5,e.quest.id());s.setString(6,e.cycle);s.setString(7,e.token);if(s.executeUpdate()!=1)throw new SQLException("Claim reservation changed");}
    }
    boolean abandon(UUID p,String id,long now)throws Exception{return transaction(p.toString(),()->{for(var e:entries(p))if(e.quest.id().equals(id)&&e.current(now)&&e.status.equals("active")){try(var s=db().prepareStatement("UPDATE codex_quest_progress SET status='ready',progress=?,token='' WHERE player=? AND quest=? AND cycle=? AND status='active'")){s.setString(1,counts(new int[e.progress.length]));s.setString(2,p.toString());s.setString(3,id);s.setString(4,e.cycle);return s.executeUpdate()==1;}}return false;});}
    /** Compare-and-swap one definition so two administrators cannot overwrite each other. */
    boolean edit(String id,String expected,QuestDefinition replacement)throws Exception{return transaction("catalog",()->{var all=catalog();var old=all.get(id);String actual=old==null?"":fingerprint(old);if(!actual.equals(expected))return false;if(old==null&&replacement!=null&&all.size()>=QuestAdminProtocol.MAX_ENTRIES)throw new IllegalArgumentException("최대 "+QuestAdminProtocol.MAX_ENTRIES+"개 의뢰");try(var s=db().prepareStatement("DELETE FROM codex_quest_catalog WHERE id=?")){s.setString(1,id);s.executeUpdate();}if(replacement!=null)try(var s=db().prepareStatement("INSERT INTO codex_quest_catalog(id,definition) VALUES(?,?)")){s.setString(1,id);s.setString(2,replacement.encode());s.executeUpdate();}return true;});}
    Map<String,QuestDefinition> release(List<String> ids,long start,long spacing,boolean enabled)throws Exception{return transaction("catalog",()->{var all=new TreeMap<>(catalog());if(ids.size()>QuestAdminProtocol.MAX_ENTRIES||new HashSet<>(ids).size()!=ids.size()||ids.stream().anyMatch(id->!all.containsKey(id)))throw new IllegalArgumentException("의뢰 ID를 확인하세요");int n=0;for(String id:ids){var q=all.get(id).release(start+spacing*n++,enabled);try(var s=db().prepareStatement("UPDATE codex_quest_catalog SET definition=? WHERE id=?")){s.setString(1,q.encode());s.setString(2,id);s.executeUpdate();}all.put(id,q);}return Map.copyOf(all);});}
    static String fingerprint(QuestDefinition q){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(q.encode().getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    void resolve(UUID p,String id,String cycle,boolean delivered)throws Exception{transaction(p.toString(),()->{for(var e:entries(p))if(e.quest.id().equals(id)&&e.cycle.equals(cycle)&&e.status.equals("paying")){finish(p,e,delivered);return null;}throw new IllegalArgumentException("검토 대기 기록 없음");});}
    /** Display history from the existing ledger, including expired daily cycles. No reward writes. */
    List<Entry> journalEntries(UUID player)throws SQLException {
        var latest=new LinkedHashMap<String,Entry>();
        try(var q=db().prepareStatement("SELECT definition,cycle,status,progress,token,completions,stage FROM codex_quest_progress WHERE player=? ORDER BY updated DESC LIMIT 2000")){
            q.setString(1,player.toString());try(var rs=q.executeQuery()){while(rs.next()){
                var definition=QuestDefinition.decode(rs.getString(1));int[] progress=Arrays.stream(rs.getString(4).split(",")).mapToInt(Integer::parseInt).toArray();
                var e=new Entry(definition,rs.getString(2),rs.getString(3),progress,rs.getString(5),rs.getInt(6),rs.getInt(7));
                if(progress.length!=definition.objectives().size())throw new SQLException("Quest progress mismatch");
                var previous=latest.get(definition.id());if(previous==null||(e.current(System.currentTimeMillis())&&Set.of("active","paying").contains(e.status())))latest.put(definition.id(),e);
            }}
        }
        return latest.values().stream().limit(600).toList();
    }
    /** Operator journal edits never invoke a payout and never erase reward completion history. */
    void adminRecord(UUID player,QuestDefinition definition,String operation,long now)throws Exception {
        transaction(player.toString(),()->{
            var all=entries(player,now);if(all.stream().anyMatch(e->e.quest().id().equals(definition.id())&&e.status().equals("paying")))throw new IllegalStateException("Resolve pending reward before editing this record");
            var old=all.stream().filter(e->e.quest().id().equals(definition.id())&&e.cycle().equals(definition.cycle(now))).findFirst().orElse(null);
            if(old!=null)definitionSnapshotCheck(old,definition);
            int count=old==null?0:old.completions();int[] progress=old==null?new int[definition.objectives().size()]:old.progress().clone();
            String status=switch(operation){case "grant","progress"->"active";case "complete"->"claimed";case "reset"->"ready";default->throw new IllegalArgumentException("Journal operation");};
            if(operation.equals("complete")){count=Math.max(count,definition.completionLimit());for(int i=0;i<progress.length;i++)progress[i]=definition.objectives().get(i).amount();}
            if(operation.equals("reset"))Arrays.fill(progress,0);
            try(var q=db().prepareStatement("DELETE FROM codex_quest_progress WHERE player=? AND quest=? AND cycle=?")){q.setString(1,player.toString());q.setString(2,definition.id());q.setString(3,definition.cycle(now));q.executeUpdate();}
            try(var q=db().prepareStatement("INSERT INTO codex_quest_progress(player,quest,cycle,definition,status,progress,token,updated,completions,stage) VALUES(?,?,?,?,?,?,?,?,?,0)")){
                q.setString(1,player.toString());q.setString(2,definition.id());q.setString(3,definition.cycle(now));q.setString(4,definition.encode());q.setString(5,status);q.setString(6,counts(progress));q.setString(7,"");q.setLong(8,now);q.setInt(9,count);q.executeUpdate();
            }return null;
        });
    }
    private static void definitionSnapshotCheck(Entry old,QuestDefinition next){if(!old.quest().encode().equals(next.encode()))throw new IllegalStateException("Use the accepted quest definition when editing history");}
    private static String counts(int[] p){return String.join(",",Arrays.stream(p).mapToObj(Integer::toString).toList());}
    private interface Tx<T>{T run()throws Exception;}
    private <T>T transaction(String player,Tx<T> action)throws Exception{
        for(int attempt=0;;attempt++){holder.begin();try{
            // A single exclusive upsert avoids INSERT IGNORE shared-lock -> UPDATE lock-upgrade deadlocks.
            String lock="INSERT INTO codex_quest_lock(player,version) VALUES(?,1) "+(maria?"ON DUPLICATE KEY UPDATE version=version+1":"ON CONFLICT(player) DO UPDATE SET version=version+1");
            try(var s=db().prepareStatement(lock)){s.setString(1,player);s.executeUpdate();}
            T value=action.run();db().commit();return value;
        }catch(Exception e){holder.rollback();if(attempt<3&&e instanceof SQLException sql&&(sql.getErrorCode()==1213||sql.getErrorCode()==1205||"40001".equals(sql.getSQLState()))){Thread.sleep(10L*(attempt+1));continue;}throw e;}finally{holder.end();}}
    }
    @Override public void close()throws SQLException{holder.close();}
}
