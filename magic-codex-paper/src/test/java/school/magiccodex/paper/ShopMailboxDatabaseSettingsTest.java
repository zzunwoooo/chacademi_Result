package school.magiccodex.paper;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import school.magiccodex.database.DatabaseSettings;
class ShopMailboxDatabaseSettingsTest {
 @TempDir Path dir;
 private String maria(String db) {
  return "mode=mariadb\nhost=db.example.invalid\nport=3306\ndatabase="+db+"\nuser=test_user\npassword=test_only_placeholder\nssl-mode=verify-full\n";
 }
 @Test void absentDedicatedFileIgnoresCommonAndPreservesSqlite() throws Exception {
  Path common=dir.resolve("database.properties");Files.writeString(common,maria("other_features"));
  byte[] original=Files.readAllBytes(common);
  for(String n:new String[]{"friends.db","mailbox.db","shops.db"})Files.write(dir.resolve(n),new byte[]{1,2,3});
  assertFalse(ShopMailboxDatabaseSettings.load(dir).mariaDb());
  assertTrue(DatabaseSettings.load(common).mariaDb());
  assertArrayEquals(original,Files.readAllBytes(common));
  for(String n:new String[]{"friends.db","mailbox.db","shops.db"})assertArrayEquals(new byte[]{1,2,3},Files.readAllBytes(dir.resolve(n)));
  assertFalse(Files.exists(dir.resolve(ShopMailboxDatabaseSettings.FILE_NAME)));
 }
 @Test void schoolAndWildShareDedicatedTargetWithDifferentCommonSettings() throws Exception {
  Path school=Files.createDirectory(dir.resolve("school")),wild=Files.createDirectory(dir.resolve("wild"));
  Files.writeString(school.resolve("database.properties"),"mode=sqlite\n");
  Files.writeString(wild.resolve("database.properties"),maria("other_features"));
  for(Path p:new Path[]{school,wild})Files.writeString(p.resolve(ShopMailboxDatabaseSettings.FILE_NAME),maria("shop_mailbox"));
  var a=ShopMailboxDatabaseSettings.load(school);var b=ShopMailboxDatabaseSettings.load(wild);
  assertTrue(a.mariaDb());assertEquals(a,b);assertTrue(a.url().contains("/shop_mailbox?"));assertTrue(a.url().contains("sslMode=verify-full"));
  assertFalse(DatabaseSettings.load(school.resolve("database.properties")).mariaDb());
  assertTrue(DatabaseSettings.load(wild.resolve("database.properties")).url().contains("/other_features?"));
 }
 @Test void invalidDedicatedSettingsFailWithoutFallback() throws Exception {
  Files.writeString(dir.resolve("database.properties"),"mode=sqlite\n");
  Files.writeString(dir.resolve(ShopMailboxDatabaseSettings.FILE_NAME),"mode=mariadb\n");
  assertThrows(java.io.IOException.class,()->ShopMailboxDatabaseSettings.load(dir));
 }
}