package school.chacademia.housing;
import org.junit.jupiter.api.*;import org.junit.jupiter.api.io.TempDir;import java.nio.file.*;import java.sql.*;import java.util.*;
import school.magiccodex.protocol.HousingProtocol.*;import static org.junit.jupiter.api.Assertions.*;
class HousingNamesTest {
 @TempDir Path temp;
 Connection db()throws Exception{return DriverManager.getConnection("jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1");}
 @Test void configMatchesBridgePrecedenceAndOptIn()throws Exception{
  assertEquals(temp.resolve("database.properties"),HousingNames.config(temp));
  Files.writeString(temp.resolve("player-state-database.properties"),"use-for-all-domains=false");assertEquals(temp.resolve("database.properties"),HousingNames.config(temp));
  Files.writeString(temp.resolve("player-state-database.properties"),"use-for-all-domains=true");assertEquals(temp.resolve("player-state-database.properties"),HousingNames.config(temp));
  Files.writeString(temp.resolve("database.properties"),"mode=sqlite");assertEquals(temp.resolve("database.properties"),HousingNames.config(temp));
 }
 @Test void offlineAndOnlineUuidRowsResolveFromAuthoritativeSqlWithoutMutation()throws Exception{
  try(var c=db()){c.createStatement().execute("CREATE TABLE nicknames(owner VARCHAR PRIMARY KEY,nickname VARCHAR,account VARCHAR)");var ids=new ArrayList<UUID>();
   try(var s=c.prepareStatement("INSERT INTO nicknames VALUES(?,?,?)")){for(int i=0;i<260;i++){var id=UUID.randomUUID();ids.add(id);s.setString(1,id.toString());s.setString(2,"한글"+i);s.setString(3,"English"+i);s.addBatch();}s.executeBatch();}
   var names=new HousingNames(c,"nicknames");var requested=new ArrayList<>(ids);requested.add(ids.getFirst());requested.add(UUID.randomUUID());var found=names.read(requested);assertEquals(260,found.size());assertEquals("한글259",found.get(ids.getLast()));
   try(var r=c.createStatement().executeQuery("SELECT COUNT(*) FROM nicknames WHERE account LIKE 'English%'")){assertTrue(r.next());assertEquals(260,r.getInt(1));}
   c.createStatement().execute("UPDATE nicknames SET nickname='변경한글' WHERE owner='"+ids.getFirst()+"'");assertEquals("변경한글",names.read(List.of(ids.getFirst())).get(ids.getFirst()));
  }
 }
 @Test void lookupFailureIsErrorAndNeverHistoricalEnglishFallback()throws Exception{try(var c=db()){var names=new HousingNames(c,"absent");assertThrows(SQLException.class,()->names.read(List.of(UUID.randomUUID())));}assertEquals("닉네임 미등록",HousingNames.shown(null));assertEquals("닉네임 미등록",HousingNames.shown(" "));}
 @Test void guestbookProjectionPreservesUuidBodyTimeAndMetadata(){var old=new Person(UUID.randomUUID(),"EnglishAccount",2,"",false);var resolved=HousingNames.resolve(old,"황준우");assertEquals("황준우",resolved.name());assertEquals(old.id(),resolved.id());assertEquals(old.house(),resolved.house());assertEquals(old.skin(),resolved.skin());assertEquals(old.slim(),resolved.slim());var entry=new Entry(12,old,1234567,"원래 방명록");var shown=new Entry(entry.id(),resolved,entry.time(),entry.text());assertEquals(entry.id(),shown.id());assertEquals(entry.time(),shown.time());assertEquals(entry.text(),shown.text());assertEquals("EnglishAccount",old.name());}
 @Test void federationNeverRanksIndependentRevisionsOrUsesAccountFallback()throws Exception{var id=UUID.randomUUID();var names=new HashMap<UUID,String>();HousingNames.merge(names,id,"한글닉");HousingNames.merge(names,id,"한글닉");assertEquals(1,names.size());assertThrows(SQLException.class,()->HousingNames.merge(names,id,"서로다른닉"));assertEquals("한글닉",names.get(id));}
 @Test void onlyKnownColocatedBridgeFoldersParticipate()throws Exception{var network=temp.resolve("network");for(String server:List.of("school","wild","arena","untrusted"))Files.createDirectories(network.resolve(server).resolve("plugins/MagicCodexBridge"));var local=network.resolve("wild/plugins/MagicCodexBridge");assertEquals(List.of(network.resolve("school/plugins/MagicCodexBridge"),local,network.resolve("arena/plugins/MagicCodexBridge")),HousingNames.networkSources(local));assertEquals(List.of(temp.toAbsolutePath()),HousingNames.networkSources(temp));}
}
