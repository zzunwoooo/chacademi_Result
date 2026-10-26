package school.magiccodex.paper;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import school.magiccodex.database.DatabaseSettings;

class DatabaseSettingsTest {
    @TempDir Path temp;
    @Test void missingFileIsVisibleAsSqliteDefault()throws Exception{
        Path file=temp.resolve("database.properties");var settings=DatabaseSettings.load(file);
        assertFalse(settings.mariaDb());assertEquals("SQLite",settings.modeName());assertEquals("",settings.target());
        String line=DatabaseSettings.describe(file,settings);
        assertTrue(line.contains("database.properties"),line);assertTrue(line.contains("파일 없음"),line);assertTrue(line.contains("SQLite"),line);
        assertEquals(line,DatabaseSettings.describe(file));
    }
    @Test void mariaDbLineShowsTargetButNeverCredentials()throws Exception{
        Path file=temp.resolve("database.properties");
        Files.writeString(file,"mode=mariadb\nhost=db.example\nport=3307\ndatabase=chaca\nuser=codex\npassword=s3cret-value\n");
        var settings=DatabaseSettings.load(file);
        assertTrue(settings.mariaDb());assertEquals("MariaDB",settings.modeName());assertEquals("db.example:3307/chaca",settings.target());
        String line=DatabaseSettings.describe(file,settings);
        assertTrue(line.contains("파일 있음"),line);assertTrue(line.contains("MariaDB"),line);assertTrue(line.contains("db.example:3307/chaca"),line);
        assertFalse(line.contains("s3cret-value"),line);assertFalse(line.contains("codex"),line);assertFalse(line.contains("Timeout"),line);
        Files.writeString(file,"mode=sqlite\n");
        assertTrue(DatabaseSettings.describe(file).contains("파일 있음"));assertTrue(DatabaseSettings.describe(file).contains("SQLite"));
    }
}
