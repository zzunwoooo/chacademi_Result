package school.magiccodex.paper;

import java.nio.file.Path;
import java.sql.*;
import java.text.Normalizer;
import java.util.*;
import school.magiccodex.database.ConnectionHolder;
import school.magiccodex.database.DatabaseSettings;
import school.magiccodex.protocol.NicknameProtocol;

/** One IO worker owns this connection (재연결은 ConnectionHolder가 맡는다); SQLite reuses the existing friends.db.
 * 닉네임은 서버 전체에서 유일하다: nickname_key(NFKC+소문자)로 대소문자·전각/반각만 다른 이름도 같은 이름으로 본다.
 * first_done은 최초 닉네임 설정(스토리 연동) 완료 여부로, 두 서버가 함께 보는 기준값이다. */
final class NicknameStore implements AutoCloseable {
    record State(String account,String nickname,long revision,boolean firstDone){}
    enum Outcome{SAVED,STALE,DUPLICATE}
    record Saved(Outcome outcome,State state){}
    private static final String LOCK="codex_nickname_save";
    private final ConnectionHolder holder;
    private final String table;
    private final boolean maria;
    /** UNIQUE 인덱스가 있으면 DB가 동시 저장까지 막는다. 기존 데이터에 중복이 있어 만들지 못하면 코드(+MariaDB 잠금)로 막는다. */
    private final boolean unique;
    private final List<String> warnings=new ArrayList<>();
    private Connection db()throws SQLException{return holder.get();}
    NicknameStore(Path file,DatabaseSettings settings)throws Exception{
        maria=settings.mariaDb();table=settings.table("nicknames","nicknames");holder=settings.holder(file);
        boolean index=false;
        try{
            try(var s=db().createStatement()){s.execute("CREATE TABLE IF NOT EXISTS "+table+" (owner "+(maria?"CHAR(36)":"TEXT")+" PRIMARY KEY,account VARCHAR(16) NOT NULL,nickname VARCHAR(16) NOT NULL,revision BIGINT NOT NULL DEFAULT 1)"+(maria?" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4":""));}
            // 추가 전용 스키마 변경: 기존 행은 그대로 두고 열만 더한다.
            column("nickname_key",maria?"VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL":"TEXT NULL");
            column("first_done",maria?"TINYINT NOT NULL DEFAULT 0":"INTEGER NOT NULL DEFAULT 0");
            backfill();
            try(var s=db().createStatement()){s.execute("CREATE UNIQUE INDEX IF NOT EXISTS "+table+"_key ON "+table+"(nickname_key)");index=true;}
            catch(SQLException e){warnings.add("닉네임 중복 방지 인덱스를 만들지 못했습니다 (기존 중복 닉네임을 정리한 뒤 재시작하면 생성됩니다. 그동안 새 저장은 코드에서 중복을 막습니다): "+e.getMessage());}
        }catch(Exception e){try{holder.close();}catch(SQLException ignored){}throw e;}
        unique=index;
    }
    /** 시작 시 발견한 문제(기존 중복 닉네임 등). 플러그인 시작을 막지 않고 경고로만 남긴다. */
    List<String> warnings(){return List.copyOf(warnings);}
    /** 비교용 키: NFKC 정규화 + 소문자. 대소문자·전각/반각·호환 문자만 다른 닉네임은 같은 키가 된다. */
    static String key(String nickname){
        String k=Normalizer.normalize(nickname,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        k=Normalizer.normalize(k,Normalizer.Form.NFKC);
        if(k.isEmpty()||k.length()>64)throw new IllegalArgumentException("사용할 수 없는 닉네임입니다. 다른 닉네임을 입력해 주세요.");
        return k;
    }
    private boolean hasColumn(String name){
        try(var s=db().createStatement();var r=s.executeQuery("SELECT "+name+" FROM "+table+" WHERE 1=0")){return true;}catch(SQLException e){return false;}
    }
    private void column(String name,String definition)throws SQLException{
        if(hasColumn(name))return;
        try(var s=db().createStatement()){s.execute("ALTER TABLE "+table+" ADD COLUMN "+name+" "+definition);}
        catch(SQLException e){if(!hasColumn(name))throw e;} // 다른 서버가 동시에 추가한 경우
    }
    /** 키가 없거나(구버전이 쓴 행) 닉네임과 맞지 않는 행을 채우고, 이미 존재하는 중복을 경고 목록에 남긴다. */
    private void backfill()throws SQLException{
        var byKey=new TreeMap<String,List<String>>();var fixes=new ArrayList<String[]>();
        try(var s=db().createStatement();var r=s.executeQuery("SELECT owner,nickname,nickname_key FROM "+table)){
            while(r.next()){
                String owner=r.getString(1),nickname=r.getString(2),stored=r.getString(3),key;
                try{key=key(nickname);}catch(RuntimeException e){continue;}
                byKey.computeIfAbsent(key,k->new ArrayList<>()).add(nickname+"("+owner+")");
                if(!key.equals(stored))fixes.add(new String[]{owner,nickname,key});
            }
        }
        for(String[] fix:fixes)try(var s=db().prepareStatement("UPDATE "+table+" SET nickname_key=? WHERE owner=? AND nickname=?")){s.setString(1,fix[2]);s.setString(2,fix[0]);s.setString(3,fix[1]);s.executeUpdate();}
            catch(SQLException e){if(!constraint(e))throw e;} // 인덱스가 이미 있고 구버전 서버가 만든 중복: 키 없이 둔다 (아래 경고에 포함)
        int listed=0;
        for(var e:byKey.entrySet())if(e.getValue().size()>1&&listed++<50)warnings.add("중복 닉네임 발견 (기존 데이터는 유지, 새 저장만 차단): "+String.join(", ",e.getValue()));
        if(listed>50)warnings.add("중복 닉네임이 "+(listed-50)+"건 더 있습니다.");
    }
    private static State state(ResultSet r,int from)throws SQLException{return new State(r.getString(from),r.getString(from+1),r.getLong(from+2),r.getInt(from+3)!=0);}
    Map<UUID,State> all()throws SQLException{
        var result=new HashMap<UUID,State>();try(var s=db().createStatement();var r=s.executeQuery("SELECT owner,account,nickname,revision,first_done FROM "+table)){while(r.next())try{result.put(UUID.fromString(r.getString(1)),state(r,2));}catch(IllegalArgumentException ignored){}}return result;
    }
    State load(UUID owner)throws SQLException{try(var s=db().prepareStatement("SELECT account,nickname,revision,first_done FROM "+table+" WHERE owner=?")){s.setString(1,owner.toString());try(var r=s.executeQuery()){return r.next()?state(r,1):null;}}}
    /** 다른 플레이어가 같은 키의 닉네임을 쓰거나, 다른 플레이어의 계정 이름과 같으면 true. */
    private boolean taken(UUID owner,String key)throws SQLException{
        try(var s=db().prepareStatement("SELECT 1 FROM "+table+" WHERE owner<>? AND (nickname_key=? OR LOWER(account)=?) LIMIT 1")){s.setString(1,owner.toString());s.setString(2,key);s.setString(3,key);try(var r=s.executeQuery()){return r.next();}}
    }
    /** 키가 비어 있는 행(구버전 서버가 방금 쓴 행)을 검사 전에 채운다. 보통 0행이다. */
    private void fillKeys()throws SQLException{
        var fixes=new ArrayList<String[]>();
        try(var s=db().createStatement();var r=s.executeQuery("SELECT owner,nickname FROM "+table+" WHERE nickname_key IS NULL")){while(r.next())try{fixes.add(new String[]{r.getString(1),r.getString(2),key(r.getString(2))});}catch(RuntimeException ignored){}}
        for(String[] fix:fixes)try(var s=db().prepareStatement("UPDATE "+table+" SET nickname_key=? WHERE owner=? AND nickname=? AND nickname_key IS NULL")){s.setString(1,fix[2]);s.setString(2,fix[0]);s.setString(3,fix[1]);s.executeUpdate();}
            catch(SQLException e){if(!constraint(e))throw e;}
    }
    private static boolean constraint(SQLException e){
        String state=e.getSQLState(),message=e.getMessage();int code=e.getErrorCode();
        return e instanceof SQLIntegrityConstraintViolationException||(state!=null&&state.startsWith("23"))||code==1062||(code&0xff)==19&&message!=null&&message.toUpperCase(Locale.ROOT).contains("UNIQUE");
    }
    /** MariaDB: 교착·잠금 대기 초과. SQLite: 같은 friends.db를 쓰는 다른 저장소(친구 등)가 조회와 쓰기 사이에 커밋하면 나는 SQLITE_BUSY(5, 확장 코드 포함). */
    private boolean retryable(SQLException e){return maria?e.getErrorCode()==1213||e.getErrorCode()==1205||"40001".equals(e.getSQLState()):(e.getErrorCode()&0xff)==5;}
    /** Revision compare occurs in SQL, including simultaneous first saves across servers.
     * 중복 검사와 쓰기는 한 트랜잭션: UNIQUE 인덱스(없으면 MariaDB 이름 잠금)가 두 서버의 동시 저장까지 막는다.
     * 본인의 현재 닉네임을 다시 저장하는 것은 중복이 아니다. */
    Saved save(UUID owner,String account,String nickname,long revision)throws SQLException{
        NicknameProtocol.validate(nickname);String key=key(nickname);
        for(int attempt=0;;attempt++){
            boolean locked=false;holder.begin();
            try{
                if(maria&&!unique){lock();locked=true;}
                fillKeys();
                if(taken(owner,key)){holder.rollback();return new Saved(Outcome.DUPLICATE,load(owner));}
                int changed;
                if(revision==0)try(var s=db().prepareStatement((maria?"INSERT IGNORE INTO ":"INSERT OR IGNORE INTO ")+table+"(owner,account,nickname,nickname_key,revision) VALUES(?,?,?,?,1)")){s.setString(1,owner.toString());s.setString(2,account);s.setString(3,nickname);s.setString(4,key);changed=s.executeUpdate();}
                else try(var s=db().prepareStatement("UPDATE "+table+" SET account=?,nickname=?,nickname_key=?,revision=revision+1 WHERE owner=? AND revision=?")){s.setString(1,account);s.setString(2,nickname);s.setString(3,key);s.setString(4,owner.toString());s.setLong(5,revision);changed=s.executeUpdate();}
                db().commit();
                // INSERT IGNORE는 UNIQUE 충돌도 조용히 0행으로 끝낸다: 다른 서버가 방금 같은 이름을 가져갔는지 다시 확인한다.
                // 커밋 뒤에 확인해야 한다: MariaDB(REPEATABLE READ)는 커밋 전에는 이 트랜잭션의 첫 조회 시점 스냅숏만 보여 상대 서버의 행이 보이지 않는다.
                boolean duplicate=changed==0&&taken(owner,key);
                return new Saved(changed==1?Outcome.SAVED:duplicate?Outcome.DUPLICATE:Outcome.STALE,load(owner));
            }catch(SQLException e){
                try{holder.rollback();}catch(SQLException ignored){}
                if(constraint(e))return new Saved(Outcome.DUPLICATE,load(owner));
                if(attempt<3&&retryable(e)){try{Thread.sleep(10L*(attempt+1));}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw e;}continue;}
                throw e;
            }finally{
                if(locked)unlock();
                holder.end();
            }
        }
    }
    private void lock()throws SQLException{
        try(var s=db().prepareStatement("SELECT GET_LOCK(?,5)")){s.setString(1,LOCK);try(var r=s.executeQuery()){if(!r.next()||r.getInt(1)!=1)throw new SQLException("Nickname lock timeout");}}
    }
    private void unlock(){
        // 연결이 끊겼다면 세션 잠금은 서버가 이미 풀었다.
        try(var s=db().prepareStatement("SELECT RELEASE_LOCK(?)")){s.setString(1,LOCK);try(var r=s.executeQuery()){r.next();}}catch(SQLException ignored){}
    }
    /** 최초 닉네임 설정 완료 기록. 이 호출이 0→1로 바꿨을 때만 true: 두 서버에서 완료 처리가 겹쳐도 한 번만 true가 된다. */
    boolean completeFirst(UUID owner)throws SQLException{
        try(var s=db().prepareStatement("UPDATE "+table+" SET first_done=1 WHERE owner=? AND first_done=0")){s.setString(1,owner.toString());return s.executeUpdate()==1;}
    }
    /** 완료 기록을 되돌린다 (완료 직후 접속이 끊겨 스토리를 전달하지 못했거나, 관리자가 강제로 다시 열 때). */
    void reopenFirst(UUID owner)throws SQLException{
        try(var s=db().prepareStatement("UPDATE "+table+" SET first_done=0 WHERE owner=?")){s.setString(1,owner.toString());s.executeUpdate();}
    }
    public void close()throws SQLException{holder.close();}
}
