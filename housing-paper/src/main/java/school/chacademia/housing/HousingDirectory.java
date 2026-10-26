package school.chacademia.housing;
import java.nio.file.*;import java.sql.*;import java.util.*;
import school.magiccodex.database.DatabaseSettings;
/** Worker-owned derived in-memory SQLite index. All operational sources are SELECT-only/SQLite mode=ro.
 * SQL search/order/LIMIT run here, never on the render or Bukkit main thread. */
final class HousingDirectory implements AutoCloseable {
 record Hit(UUID id,String name,boolean online,boolean friend){}
 record Slice(List<Hit> hits,boolean more){}
 final Path local;Connection index;long refreshed;
 HousingDirectory(Path local){this.local=local;}
 static Connection source(Path folder)throws Exception{Class.forName("org.sqlite.JDBC");var settings=DatabaseSettings.load(HousingNames.config(folder));if(settings.mariaDb()){var c=settings.connect(folder.resolve("friends.db"));c.setReadOnly(true);return c;}var f=folder.resolve("friends.db");if(!Files.isRegularFile(f))throw new SQLException("Identity source missing");var props=new Properties();props.setProperty("open_mode","65");props.setProperty("busy_timeout","1000");var c=DriverManager.getConnection("jdbc:sqlite:"+f.toUri().toASCIIString()+"?mode=ro",props);c.createStatement().execute("PRAGMA query_only=ON");return c;}
 void refresh()throws Exception{
  if(index!=null&&System.currentTimeMillis()-refreshed<5000)return;Class.forName("org.sqlite.JDBC");var next=DriverManager.getConnection("jdbc:sqlite::memory:");
  try{next.createStatement().execute("CREATE TABLE directory(owner TEXT PRIMARY KEY,name TEXT NOT NULL,conflict INT NOT NULL DEFAULT 0)");
   try(var insert=next.prepareStatement("INSERT INTO directory(owner,name) VALUES(?,?) ON CONFLICT(owner) DO UPDATE SET conflict=CASE WHEN name<>excluded.name THEN 1 ELSE conflict END")){
    for(var folder:HousingNames.networkSources(local)){var settings=DatabaseSettings.load(HousingNames.config(folder));try(var c=source(folder);var s=c.createStatement();var r=s.executeQuery("SELECT owner,nickname FROM "+settings.table("nicknames","nicknames"))){int batch=0;while(r.next()){String id=r.getString(1),name=r.getString(2);if(name==null||name.isBlank())continue;UUID.fromString(id);insert.setString(1,id);insert.setString(2,name);insert.addBatch();if(++batch%256==0)insert.executeBatch();}insert.executeBatch();}}
   }
   next.createStatement().execute("CREATE INDEX directory_name ON directory(name)");next.createStatement().execute("CREATE TABLE online(owner TEXT PRIMARY KEY)");next.createStatement().execute("CREATE TABLE friends(owner TEXT PRIMARY KEY)");
  }catch(Exception e){next.close();throw e;}if(index!=null)index.close();index=next;refreshed=System.currentTimeMillis();
 }
 Set<UUID> friends(UUID viewer)throws Exception{var settings=DatabaseSettings.load(HousingNames.config(local));var out=new HashSet<UUID>();try(var c=source(local);var s=c.prepareStatement("SELECT target FROM "+settings.table("friends","friends")+" WHERE owner=?")){s.setString(1,viewer.toString());try(var r=s.executeQuery()){while(r.next())out.add(UUID.fromString(r.getString(1)));}}return Set.copyOf(out);}
 private void replace(String table,Set<UUID> ids)throws SQLException{index.createStatement().execute("DELETE FROM "+table);try(var s=index.prepareStatement("INSERT INTO "+table+" VALUES(?)")){for(var id:ids){s.setString(1,id.toString());s.addBatch();}s.executeBatch();}}
 Slice search(UUID viewer,String query,int page,Set<UUID> online)throws Exception{
  refresh();replace("online",online);replace("friends",friends(viewer));
  try(var r=index.createStatement().executeQuery("SELECT 1 FROM directory WHERE conflict<>0 LIMIT 1")){if(r.next())throw new SQLException("Conflicting nickname sources");}
  var out=new ArrayList<Hit>();
  try(var s=index.prepareStatement("SELECT d.owner,d.name,o.owner IS NOT NULL,f.owner IS NOT NULL FROM directory d LEFT JOIN online o ON o.owner=d.owner LEFT JOIN friends f ON f.owner=d.owner WHERE instr(lower(d.name),lower(?))>0 ORDER BY CASE WHEN o.owner IS NOT NULL AND f.owner IS NOT NULL THEN 0 WHEN o.owner IS NOT NULL THEN 1 WHEN f.owner IS NOT NULL THEN 2 ELSE 3 END,d.name COLLATE NOCASE,d.owner LIMIT ? OFFSET ?")){
   s.setString(1,query.strip());s.setInt(2,31);s.setInt(3,Math.multiplyExact(page,30));try(var r=s.executeQuery()){while(r.next())out.add(new Hit(UUID.fromString(r.getString(1)),r.getString(2),r.getBoolean(3),r.getBoolean(4)));}
  }boolean more=out.size()>30;if(more)out.removeLast();return new Slice(List.copyOf(out),more);
 }
 public void close()throws SQLException{if(index!=null)index.close();}
}
