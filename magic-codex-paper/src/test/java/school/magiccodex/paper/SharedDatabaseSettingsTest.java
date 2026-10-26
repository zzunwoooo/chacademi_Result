package school.magiccodex.paper;
import java.nio.file.*;import java.io.IOException;import org.junit.jupiter.api.Test;import org.junit.jupiter.api.io.TempDir;import static org.junit.jupiter.api.Assertions.*;import school.magiccodex.database.DatabaseSettings;
class SharedDatabaseSettingsTest {
 @TempDir Path dir;
 @Test void missingSharedConfigDoesNotCreateLocalDatabase()throws Exception {var p=dir.resolve("database.properties");var error=assertThrows(IOException.class,()->DatabaseSettings.loadShared(p,"books"));assertTrue(error.getMessage().contains("fallback refused"));assertFalse(Files.exists(p));assertEquals(0,Files.list(dir).count());}
 @Test void explicitSqliteAndInvalidMariaConfigFailClosed()throws Exception {var p=dir.resolve("database.properties");Files.writeString(p,"mode=sqlite\n");assertThrows(IOException.class,()->DatabaseSettings.loadShared(p,"player-state"));Files.writeString(p,"mode=mariadb\n");assertThrows(IOException.class,()->DatabaseSettings.loadShared(p,"player-state"));}
 @Test void validSharedSettingsRemainSameTargetAndNeverExposeCredentials()throws Exception {var p=dir.resolve("database.properties");Files.writeString(p,"mode=mariadb\nhost=127.0.0.1\nport=3306\ndatabase=test_shared\nuser=test_user\npassword=fixture-only\n");var settings=DatabaseSettings.loadShared(p,"books");assertTrue(settings.mariaDb());assertEquals("127.0.0.1:3306/test_shared",settings.target());assertFalse(DatabaseSettings.describe(p,settings).contains("fixture-only"));}
}
