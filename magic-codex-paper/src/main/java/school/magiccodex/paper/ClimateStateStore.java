package school.magiccodex.paper;

import java.sql.SQLException;
import school.magiccodex.database.ConnectionHolder;
import school.magiccodex.database.DatabaseSettings;

/** MariaDB 전용 작은 키/값 표 (codex_shared_state). 계절처럼 두 서버가 함께 보는 상태를 담는다.
 * 계절 전용 IO 스레드 하나만 사용한다; 메인 스레드에서 호출하지 않는다. */
final class ClimateStateStore implements AutoCloseable {
    private final ConnectionHolder holder;
    ClimateStateStore(DatabaseSettings settings)throws Exception{
        if(!settings.mariaDb())throw new IllegalArgumentException("MariaDB required");
        holder=settings.holder(null);
        try(var s=holder.get().createStatement()){
            s.execute("CREATE TABLE IF NOT EXISTS codex_shared_state(state_key VARCHAR(64) PRIMARY KEY,state_value VARCHAR(255) NOT NULL,revision BIGINT NOT NULL DEFAULT 0,updated_at BIGINT NOT NULL DEFAULT 0) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        }catch(SQLException e){try{holder.close();}catch(SQLException ignored){}throw e;}
    }
    /** 저장된 값, 없으면 null. */
    String read(String key)throws SQLException{
        try(var s=holder.get().prepareStatement("SELECT state_value FROM codex_shared_state WHERE state_key=?")){s.setString(1,key);try(var r=s.executeQuery()){return r.next()?r.getString(1):null;}}
    }
    /** 값이 아직 없을 때만 넣는다 (먼저 시작한 서버의 값이 기준이 된다). */
    void seed(String key,String value)throws SQLException{
        try(var s=holder.get().prepareStatement("INSERT IGNORE INTO codex_shared_state(state_key,state_value,revision,updated_at) VALUES(?,?,1,?)")){s.setString(1,key);s.setString(2,value);s.setLong(3,System.currentTimeMillis());s.executeUpdate();}
    }
    /** 관리자 변경: 마지막에 쓴 값이 이긴다. */
    void write(String key,String value)throws SQLException{
        try(var s=holder.get().prepareStatement("INSERT INTO codex_shared_state(state_key,state_value,revision,updated_at) VALUES(?,?,1,?) ON DUPLICATE KEY UPDATE state_value=VALUES(state_value),revision=revision+1,updated_at=VALUES(updated_at)")){s.setString(1,key);s.setString(2,value);s.setLong(3,System.currentTimeMillis());s.executeUpdate();}
    }
    @Override public void close()throws SQLException{holder.close();}
}
