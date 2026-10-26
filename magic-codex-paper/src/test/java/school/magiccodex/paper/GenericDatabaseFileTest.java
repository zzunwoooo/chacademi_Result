package school.magiccodex.paper;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class GenericDatabaseFileTest {
    @TempDir Path temp;
    static final String MARIA="mode=mariadb\nhost=db.example\nport=3306\ndatabase=chaca\nuser=codex\npassword=secret\n";
    @Test void noFilesStaySqliteOnGenericFile()throws Exception{
        assertEquals(temp.resolve("database.properties"),GenericDatabaseFile.resolve(temp));
        assertFalse(GenericDatabaseFile.load(temp).mariaDb());
    }
    @Test void dedicatedFileWithoutOptInIsIgnored()throws Exception{
        Files.writeString(temp.resolve("player-state-database.properties"),MARIA);
        assertEquals(temp.resolve("database.properties"),GenericDatabaseFile.resolve(temp));
        assertFalse(GenericDatabaseFile.load(temp).mariaDb());
        Files.writeString(temp.resolve("player-state-database.properties"),MARIA+"use-for-all-domains=false\n");
        assertFalse(GenericDatabaseFile.load(temp).mariaDb());
    }
    @Test void optInUsesDedicatedConnection()throws Exception{
        Files.writeString(temp.resolve("player-state-database.properties"),MARIA+"use-for-all-domains=true\n");
        assertEquals(temp.resolve("player-state-database.properties"),GenericDatabaseFile.resolve(temp));
        var settings=GenericDatabaseFile.load(temp);
        assertTrue(settings.mariaDb());assertEquals("db.example:3306/chaca",settings.target());
    }
    @Test void explicitGenericFileWins()throws Exception{
        Files.writeString(temp.resolve("player-state-database.properties"),MARIA+"use-for-all-domains=true\n");
        Files.writeString(temp.resolve("database.properties"),"mode=sqlite\n");
        assertEquals(temp.resolve("database.properties"),GenericDatabaseFile.resolve(temp));
        assertFalse(GenericDatabaseFile.load(temp).mariaDb());
    }
}
