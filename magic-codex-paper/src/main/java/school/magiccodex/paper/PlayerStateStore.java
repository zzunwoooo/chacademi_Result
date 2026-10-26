package school.magiccodex.paper;

import java.sql.*;
import java.util.*;
import school.magiccodex.database.ConnectionHolder;
import school.magiccodex.database.DatabaseSettings;

/** Only persistent, original player-owned values; derived equipment/season stats stay in memory. */
final class PlayerStateStore implements AutoCloseable {
    /** Artifact state shared between school and wild (2026-10-15): equipped ids, use cooldowns, visited biomes
     *  (traveler_notebook), the item sturdy_strap is holding. Ownership itself is a LuckPerms permission (already shared). */
    record Artifacts(String equipped,String cooldowns,String biomes,byte[] kept,String runtime) {
        static final Artifacts EMPTY=new Artifacts("","","",null,"");
        Artifacts(String equipped,String cooldowns,String biomes,byte[] kept){this(equipped,cooldowns,biomes,kept,"");}
        Artifacts {
            equipped=equipped==null?"":equipped;cooldowns=cooldowns==null?"":cooldowns;biomes=biomes==null?"":biomes;runtime=runtime==null?"":runtime;
            if(equipped.length()>512||cooldowns.length()>4096||biomes.length()>16384||runtime.length()>4096||(kept!=null&&kept.length>32768))throw new IllegalArgumentException("Invalid artifact state");
            kept=kept==null?null:kept.clone();
        }
    }
    record State(int circle,double manaCurrent,double manaMaximum,double manaRegeneration,double magicHaste,
                 String cooldowns,byte[][] accessories,int[] reconfigurationCredits,Artifacts artifacts,Double health) {
        State(int circle,double manaCurrent,double manaMaximum,double manaRegeneration,double magicHaste,String cooldowns,byte[][] accessories,int[] reconfigurationCredits){
            this(circle,manaCurrent,manaMaximum,manaRegeneration,magicHaste,cooldowns,accessories,reconfigurationCredits,Artifacts.EMPTY,null);
        }
        State {
            if(artifacts==null)artifacts=Artifacts.EMPTY;
            if(health!=null&&(!Double.isFinite(health)||health<=0||health>1_000_000))health=null; // current health for PlayerStats.arrived
            if(circle<1||circle>9||!valid(manaCurrent)||!valid(manaMaximum)||!valid(manaRegeneration)||!valid(magicHaste)
                    ||cooldowns==null||cooldowns.length()>32768||accessories==null||accessories.length!=4
                    ||reconfigurationCredits==null||reconfigurationCredits.length!=3)throw new IllegalArgumentException("Invalid player state");
            accessories=Arrays.stream(accessories).map(b->b==null?null:b.clone()).toArray(byte[][]::new);
            for(var slot:accessories)if(slot!=null&&slot.length>32768)throw new IllegalArgumentException("Accessory too large");
            reconfigurationCredits=reconfigurationCredits.clone();
            for(int credit:reconfigurationCredits)if(credit<0||credit>1_000_000)throw new IllegalArgumentException("Invalid credit");
        }
        private static boolean valid(double n){return Double.isFinite(n)&&n>=0&&n<=1_000_000;}
    }
    /** UPDATE가 0행: 다른 서버가 임대를 가져갔다는 확정 신호. 일시적인 SQL/연결 오류와 구분한다. */
    static final class LeaseLostException extends SQLException {
        LeaseLostException(UUID id){super("Player state lease lost: "+id);}
    }
    private static final String COLUMNS="player,circle,mana_current,mana_maximum,mana_regeneration,magic_haste,mana_cooldowns,accessory_0,accessory_1,accessory_2,accessory_3,reconfig_credit_0,reconfig_credit_1,reconfig_credit_2,artifact_equipped,artifact_cooldowns,artifact_biomes,artifact_kept,artifact_runtime,health";
    private final ConnectionHolder holder;
    private Connection db()throws SQLException{return holder.get();}
    PlayerStateStore(DatabaseSettings settings)throws Exception {
        if(!settings.mariaDb())throw new IllegalArgumentException("MariaDB required");
        holder=settings.holder(null);
        try(var s=db().createStatement()){
            s.execute("CREATE TABLE IF NOT EXISTS codex_player_state(player CHAR(36) PRIMARY KEY,circle TINYINT NOT NULL DEFAULT 1,mana_current DOUBLE NOT NULL DEFAULT 100,mana_maximum DOUBLE NOT NULL DEFAULT 100,mana_regeneration DOUBLE NOT NULL DEFAULT 5,magic_haste DOUBLE NOT NULL DEFAULT 0,mana_cooldowns TEXT NOT NULL,accessory_0 BLOB NULL,accessory_1 BLOB NULL,accessory_2 BLOB NULL,accessory_3 BLOB NULL,reconfig_credit_0 INT NOT NULL DEFAULT 0,reconfig_credit_1 INT NOT NULL DEFAULT 0,reconfig_credit_2 INT NOT NULL DEFAULT 0,lease_token CHAR(36) NULL,lease_until BIGINT NOT NULL DEFAULT 0,revision BIGINT NOT NULL DEFAULT 0) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            // 2026-10-15 아티팩트 공유 상태: 기존 행은 빈 값으로 시작 (MariaDB ADD COLUMN IF NOT EXISTS, 다시 실행해도 안전)
            s.execute("ALTER TABLE codex_player_state ADD COLUMN IF NOT EXISTS artifact_equipped VARCHAR(512) NULL DEFAULT NULL,"
                    +" ADD COLUMN IF NOT EXISTS artifact_cooldowns TEXT NULL, ADD COLUMN IF NOT EXISTS artifact_biomes TEXT NULL, ADD COLUMN IF NOT EXISTS artifact_kept BLOB NULL,"
                    +" ADD COLUMN IF NOT EXISTS artifact_runtime TEXT NULL, ADD COLUMN IF NOT EXISTS health DOUBLE NULL");
        }
    }
    /** Import legacy local artifacts once, only while this server owns the player lease. */
    void importLegacyArtifacts(UUID id,Artifacts a,String token)throws SQLException {
        try(var q=db().prepareStatement("UPDATE codex_player_state SET artifact_equipped=?,artifact_cooldowns=?,artifact_biomes=?,artifact_kept=?,artifact_runtime=? WHERE player=? AND lease_token=? AND artifact_equipped IS NULL")){
            q.setString(1,a.equipped());q.setString(2,a.cooldowns());q.setString(3,a.biomes());q.setBytes(4,a.kept());q.setString(5,a.runtime());q.setString(6,id.toString());q.setString(7,token);q.executeUpdate();
        }
    }
    Optional<State> load(UUID id)throws SQLException {
        try(var s=db().prepareStatement("SELECT "+COLUMNS+" FROM codex_player_state WHERE player=?")){
            s.setString(1,id.toString());try(var r=s.executeQuery()){
                if(!r.next())return Optional.empty();
                var slots=new byte[4][];for(int i=0;i<4;i++)slots[i]=r.getBytes(8+i);
                var credits=new int[3];for(int i=0;i<3;i++)credits[i]=r.getInt(12+i);
                var artifacts=new Artifacts(r.getString(15),r.getString(16),r.getString(17),r.getBytes(18),r.getString(19));
                double hp=r.getDouble(20);Double health=r.wasNull()?null:hp;
                return Optional.of(new State(r.getInt(2),r.getDouble(3),r.getDouble(4),r.getDouble(5),r.getDouble(6),r.getString(7),slots,credits,artifacts,health));
            }
        }
    }
    State firstOrCurrent(UUID id,State fallback)throws SQLException {
        try(var s=db().prepareStatement("INSERT IGNORE INTO codex_player_state("+COLUMNS+") VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")){
            bind(s,id,fallback);s.executeUpdate();
        }
        return load(id).orElseThrow(()->new SQLException("Player state insert failed"));
    }
    boolean claim(UUID id,String token)throws SQLException {
        try(var s=db().prepareStatement("UPDATE codex_player_state SET lease_token=?,lease_until=? WHERE player=? AND (lease_token IS NULL OR lease_until<? OR lease_token=?)")){
            long now=System.currentTimeMillis();s.setString(1,token);s.setLong(2,now+60_000);s.setString(3,id.toString());s.setLong(4,now);s.setString(5,token);
            return s.executeUpdate()==1;
        }
    }
    void release(UUID id,String token)throws SQLException {
        try(var s=db().prepareStatement("UPDATE codex_player_state SET lease_token=NULL,lease_until=0 WHERE player=? AND lease_token=?")){
            s.setString(1,id.toString());s.setString(2,token);s.executeUpdate();
        }
    }
    /** 관리자 확인용 잠금 상태. 행이 없으면 null. */
    record Lease(String token,long until,long revision){boolean held(long now){return token!=null&&until>=now;}}
    Lease lease(UUID id)throws SQLException {
        try(var s=db().prepareStatement("SELECT lease_token,lease_until,revision FROM codex_player_state WHERE player=?")){
            s.setString(1,id.toString());try(var r=s.executeQuery()){return r.next()?new Lease(r.getString(1),r.getLong(2),r.getLong(3)):null;}
        }
    }
    /** 관리자: 토큰과 상관없이 잠금을 푼다 (이전 서버가 비정상 종료해 잠금이 남았을 때). 풀린 행이 있으면 true. */
    boolean forceRelease(UUID id)throws SQLException {
        try(var s=db().prepareStatement("UPDATE codex_player_state SET lease_token=NULL,lease_until=0 WHERE player=? AND lease_token IS NOT NULL")){
            s.setString(1,id.toString());return s.executeUpdate()==1;
        }
    }
    /** @throws LeaseLostException 이 토큰이 더 이상 행을 소유하지 않을 때만. 그 밖의 SQLException은 일시 오류로 다시 시도할 수 있다. */
    void save(UUID id,State state,String token,boolean release)throws SQLException {
        String updates="circle=?,mana_current=?,mana_maximum=?,mana_regeneration=?,magic_haste=?,mana_cooldowns=?,"
                +"accessory_0=?,accessory_1=?,accessory_2=?,accessory_3=?,reconfig_credit_0=?,reconfig_credit_1=?,reconfig_credit_2=?,"
                +"artifact_equipped=?,artifact_cooldowns=?,artifact_biomes=?,artifact_kept=?,artifact_runtime=?,health=?,";
        String sql="UPDATE codex_player_state SET "+updates+(release?"lease_token=NULL,lease_until=0,":"lease_until=?,")
                +"revision=revision+1 WHERE player=? AND lease_token=?";
        try(var s=db().prepareStatement(sql)){
            s.setInt(1,state.circle());s.setDouble(2,state.manaCurrent());s.setDouble(3,state.manaMaximum());s.setDouble(4,state.manaRegeneration());s.setDouble(5,state.magicHaste());s.setString(6,state.cooldowns());
            for(int i=0;i<4;i++)s.setBytes(7+i,state.accessories()[i]);
            for(int i=0;i<3;i++)s.setInt(11+i,state.reconfigurationCredits()[i]);
            var a=state.artifacts();s.setString(14,a.equipped());s.setString(15,a.cooldowns());s.setString(16,a.biomes());s.setBytes(17,a.kept());s.setString(18,a.runtime());setHealth(s,19,state.health());
            int index=20;if(!release)s.setLong(index++,System.currentTimeMillis()+60_000);
            s.setString(index++,id.toString());s.setString(index,token);
            if(s.executeUpdate()!=1)throw new LeaseLostException(id);
        }
    }
    private static void bind(PreparedStatement s,UUID id,State state)throws SQLException {
        s.setString(1,id.toString());s.setInt(2,state.circle());s.setDouble(3,state.manaCurrent());s.setDouble(4,state.manaMaximum());
        s.setDouble(5,state.manaRegeneration());s.setDouble(6,state.magicHaste());s.setString(7,state.cooldowns());
        for(int i=0;i<4;i++)s.setBytes(8+i,state.accessories()[i]);
        for(int i=0;i<3;i++)s.setInt(12+i,state.reconfigurationCredits()[i]);
        var a=state.artifacts();s.setString(15,a.equipped());s.setString(16,a.cooldowns());s.setString(17,a.biomes());s.setBytes(18,a.kept());s.setString(19,a.runtime());setHealth(s,20,state.health());
    }
    private static void setHealth(PreparedStatement s,int index,Double health)throws SQLException{if(health==null)s.setNull(index,Types.DOUBLE);else s.setDouble(index,health);}
    @Override public void close()throws SQLException{holder.close();}
}
