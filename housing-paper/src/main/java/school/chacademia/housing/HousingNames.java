package school.chacademia.housing;
import java.util.*;import java.nio.file.*;import java.sql.*;
import school.magiccodex.database.DatabaseSettings;
import school.magiccodex.protocol.HousingProtocol.Person;
/** Read-only federation of the colocated network's actual Bridge nickname domains; Housing DB worker only.
 * Revisions are local to each DB and cannot order conflicting names across independent stores. */
final class HousingNames implements AutoCloseable {
 private final Path folder;private List<Source> sources;
 HousingNames(Path folder){this.folder=folder;}
 HousingNames(Connection db,String table){this.folder=null;this.sources=List.of(new Source(db,table));}
 static Path config(Path folder)throws Exception{
  var generic=folder.resolve("database.properties");if(Files.exists(generic))return generic;
  var dedicated=folder.resolve("player-state-database.properties");var props=new Properties();
  if(Files.exists(dedicated)){try(var in=Files.newInputStream(dedicated)){props.load(in);}}
  return Boolean.parseBoolean(props.getProperty("use-for-all-domains","false").trim())?dedicated:generic;
 }
 static List<Path> networkSources(Path folder){
  var local=folder.toAbsolutePath().normalize();var plugins=local.getParent();var server=plugins==null?null:plugins.getParent();var network=server==null?null:server.getParent();
  if(network==null||!network.getFileName().toString().equalsIgnoreCase("network")||!plugins.getFileName().toString().equalsIgnoreCase("plugins")||!Set.of("school","wild1","wild2","arena").contains(server.getFileName().toString()))return List.of(local);
  var out=new ArrayList<Path>();for(String name:List.of("school","wild1","wild2","arena")){var source=network.resolve(name).resolve("plugins/MagicCodexBridge");if(Files.isDirectory(source))out.add(source);}
  return out.isEmpty()?List.of(local):List.copyOf(out);
 }
 static void merge(Map<UUID,String> out,UUID id,String name)throws SQLException{
  if(name==null||name.isBlank())return;var previous=out.putIfAbsent(id,name);
  if(previous!=null&&!previous.equals(name))throw new SQLException("Conflicting registered nickname sources for UUID "+id);
 }
 private List<Source> sources(){if(sources==null)sources=networkSources(folder).stream().map(Source::new).toList();return sources;}
 Map<UUID,String> read(Collection<UUID> ids)throws SQLException{
  var keys=ids.stream().distinct().toList();var out=new HashMap<UUID,String>();if(keys.isEmpty())return out;
  try{for(var source:sources()){var c=source.connection();for(int offset=0;offset<keys.size();offset+=128){var batch=keys.subList(offset,Math.min(offset+128,keys.size()));
   try(var s=c.prepareStatement("SELECT owner,nickname FROM "+source.table+" WHERE owner IN ("+String.join(",",Collections.nCopies(batch.size(),"?"))+")")){
    for(int i=0;i<batch.size();i++)s.setString(i+1,batch.get(i).toString());try(var r=s.executeQuery()){while(r.next())merge(out,UUID.fromString(r.getString(1)),r.getString(2));}
   }
  }}return Map.copyOf(out);}catch(Exception e){try{close();}catch(Exception ignored){}throw new SQLException("Global registered nickname lookup failed: "+e.getMessage(),e);}
 }
 static String shown(String registered){return registered==null||registered.isBlank()?"닉네임 미등록":registered;}
 static Person resolve(Person original,String registered){return new Person(original.id(),shown(registered),original.house(),original.skin(),original.slim());}
 public void close()throws SQLException{if(sources!=null){SQLException error=null;for(var source:sources)try{source.close();}catch(SQLException e){error=e;}if(error!=null)throw error;}}
 private static final class Source implements AutoCloseable{
  final Path folder;Connection db;String table;
  Source(Path folder){this.folder=folder;}Source(Connection db,String table){this.folder=null;this.db=db;this.table=table;}
  Connection connection()throws Exception{
   if(db!=null&&!db.isClosed()&&db.isValid(2))return db;close();var settings=DatabaseSettings.load(config(folder));table=settings.table("nicknames","nicknames");
   if(settings.mariaDb()){db=settings.connect(folder.resolve("friends.db"));db.setReadOnly(true);}
   else{var file=folder.resolve("friends.db").toAbsolutePath();if(!Files.isRegularFile(file))throw new SQLException("Nickname source unavailable: "+folder);Class.forName("org.sqlite.JDBC");var props=new Properties();props.setProperty("open_mode","65");props.setProperty("busy_timeout","1000");db=DriverManager.getConnection("jdbc:sqlite:"+file.toUri().toASCIIString()+"?mode=ro",props);try(var s=db.createStatement()){s.execute("PRAGMA query_only=ON");}}
   return db;
  }
  public void close()throws SQLException{if(db!=null){try{db.close();}finally{db=null;}}}
 }
}
