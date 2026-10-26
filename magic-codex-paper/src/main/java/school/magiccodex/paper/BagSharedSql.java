package school.magiccodex.paper;

import java.sql.*;
import java.util.*;

/** Planned shared schema; never auto-created by plugin startup. Explicit staging/migration installation only. */
final class BagSharedSql {
    record Row(UUID head,long sequence,int active,byte[][] pages,UUID lease,String server,UUID nativeSnapshot,String nativeHash) { }
    private final Connection c; private final boolean maria;
    BagSharedSql(Connection c,boolean maria){this.c=c;this.maria=maria;}
    void createSchemaForStaging() throws SQLException {
        String blob=maria?"MEDIUMBLOB":"BLOB";
        try(var s=c.createStatement()) {s.execute("CREATE TABLE IF NOT EXISTS codex_bag_shared_v1 (uuid VARCHAR(36) PRIMARY KEY,head VARCHAR(36) NOT NULL,seq BIGINT NOT NULL,active INT NOT NULL,page1 "+blob+",page2 "+blob+",page3 "+blob+",lease VARCHAR(36),server_id VARCHAR(48),native_snapshot VARCHAR(36),native_hash VARCHAR(64),capture_id VARCHAR(36),capture_number BIGINT NOT NULL DEFAULT 0)"+(maria?" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE utf8mb4_unicode_ci":""));}
    }
    private interface Work<T>{T run()throws SQLException;}
    private <T>T tx(Work<T>w)throws SQLException{boolean auto=c.getAutoCommit();c.setAutoCommit(false);try{T out=w.run();c.commit();return out;}catch(SQLException|RuntimeException ex){try{c.rollback();}catch(SQLException suppressed){ex.addSuppressed(suppressed);}throw ex;}finally{c.setAutoCommit(auto);}}
    private static UUID uuid(String s){return s==null?null:UUID.fromString(s);}
    private static byte[] bytes(byte[] b){return b==null?new byte[0]:b;}
    Row load(UUID owner)throws SQLException{return load(owner,false);}
    private Row load(UUID owner,boolean lock)throws SQLException {
        try(var p=c.prepareStatement("SELECT head,seq,active,page1,page2,page3,lease,server_id,native_snapshot,native_hash FROM codex_bag_shared_v1 WHERE uuid=?"+(lock&&maria?" FOR UPDATE":""))){p.setString(1,owner.toString());try(var s=p.executeQuery()){if(!s.next())return null;return new Row(uuid(s.getString(1)),s.getLong(2),s.getInt(3),new byte[][]{bytes(s.getBytes(4)),bytes(s.getBytes(5)),bytes(s.getBytes(6))},uuid(s.getString(7)),s.getString(8),uuid(s.getString(9)),s.getString(10));}}
    }
    private static boolean matches(Row r,BagSnapshotBinding b){return r!=null&&r.head().equals(b.head())&&r.sequence()==b.sequence()&&r.active()==b.active();}
    /** Explicit clean-start enrollment only, after the caller proves no native snapshot exists. Never replaces a row. */
    boolean bootstrapClean(UUID owner,UUID head,UUID lease,String server)throws SQLException {
        if(!server.matches("[a-z0-9_-]{1,48}"))throw new IllegalArgumentException("Invalid server");
        return tx(()->{
            String sql=(maria?"INSERT IGNORE":"INSERT OR IGNORE")+" INTO codex_bag_shared_v1(uuid,head,seq,active,page1,page2,page3,lease,server_id,capture_number) VALUES(?,?,0,1,?,?,?,?,?,0)";
            try(var p=c.prepareStatement(sql)){p.setString(1,owner.toString());p.setString(2,head.toString());for(int i=0;i<3;i++)p.setBytes(3+i,new byte[0]);p.setString(6,lease.toString());p.setString(7,server);return p.executeUpdate()==1;}
        });
    }
    /** Claim only an acknowledged native snapshot, never a wall-clock winner or a local-WAL guess. */
    Row claim(UUID owner,BagSnapshotBinding b,UUID lease,String server)throws SQLException {
        if(!owner.equals(b.owner())||!server.matches("[a-z0-9_-]{1,48}"))throw new IllegalArgumentException("Invalid bag claim");
        return tx(()->{var row=load(owner,true);if(!matches(row,b)||row.lease()!=null||!b.snapshot().equals(row.nativeSnapshot())||!b.inventoryHash().equals(row.nativeHash()))return null;
            try(var p=c.prepareStatement("UPDATE codex_bag_shared_v1 SET lease=?,server_id=?,capture_id=NULL,capture_number=0 WHERE uuid=? AND head=? AND lease IS NULL")){p.setString(1,lease.toString());p.setString(2,server);p.setString(3,owner.toString());p.setString(4,b.head().toString());if(p.executeUpdate()!=1)return null;}return load(owner,false);});
    }
    boolean switchPages(UUID owner,UUID lease,UUID expectedHead,UUID nextHead,long nextSequence,int active,byte[][] pages)throws SQLException {
        if(nextHead.equals(expectedHead)||active<1||active>3||pages.length!=3||!BagPageCodec.isEmpty(pages[active-1]))throw new IllegalArgumentException("Invalid shared page transition");
        for(byte[] page:pages)BagPageCodec.decodePage(page);
        return tx(()->{var row=load(owner,true);if(row==null||!lease.equals(row.lease())||!expectedHead.equals(row.head())||nextSequence!=row.sequence()+1)return false;
            try(var p=c.prepareStatement("UPDATE codex_bag_shared_v1 SET head=?,seq=?,active=?,page1=?,page2=?,page3=?,native_snapshot=NULL,native_hash=NULL,capture_id=NULL,capture_number=0 WHERE uuid=? AND lease=? AND head=?")){p.setString(1,nextHead.toString());p.setLong(2,nextSequence);p.setInt(3,active);for(int i=0;i<3;i++)p.setBytes(4+i,pages[i]);p.setString(7,owner.toString());p.setString(8,lease.toString());p.setString(9,expectedHead.toString());return p.executeUpdate()==1;}});
    }
    /** Fence asynchronous captures; a delayed older capture cannot become the authoritative native snapshot. */
    boolean reserveCapture(UUID owner,UUID lease,long number,BagSnapshotBinding b)throws SQLException {
        if(number<=0||!owner.equals(b.owner()))throw new IllegalArgumentException("Invalid capture fence");
        return tx(()->{var row=load(owner,true);if(!matches(row,b)||!lease.equals(row.lease()))return false;
            try(var p=c.prepareStatement("UPDATE codex_bag_shared_v1 SET capture_id=?,capture_number=? WHERE uuid=? AND lease=? AND head=? AND capture_number<?")){p.setString(1,b.snapshot().toString());p.setLong(2,number);p.setString(3,owner.toString());p.setString(4,lease.toString());p.setString(5,b.head().toString());p.setLong(6,number);return p.executeUpdate()==1;}});
    }
    /** Caller must first read-verify the exact ID+inventory block in both native DB and Redis. */
    boolean acknowledgeNative(UUID owner,UUID lease,BagSnapshotBinding b,UUID databaseSnapshot,String databaseInventory,UUID redisSnapshot,String redisInventory,boolean release)throws SQLException {
        b.verify(owner,databaseSnapshot,databaseInventory);b.verify(owner,redisSnapshot,redisInventory);
        return tx(()->{var row=load(owner,true);if(!matches(row,b)||!lease.equals(row.lease()))return false;
            String sql="UPDATE codex_bag_shared_v1 SET native_snapshot=?,native_hash=?"+(release?",lease=NULL,server_id=NULL":"")+" WHERE uuid=? AND lease=? AND head=? AND capture_id=?";
            try(var p=c.prepareStatement(sql)){p.setString(1,b.snapshot().toString());p.setString(2,b.inventoryHash());p.setString(3,owner.toString());p.setString(4,lease.toString());p.setString(5,b.head().toString());p.setString(6,b.snapshot().toString());return p.executeUpdate()==1;}});
    }
    // There is intentionally no automatic bootstrap, per-server import, TTL stealing, unconditional upsert or WAL replay method.
}
