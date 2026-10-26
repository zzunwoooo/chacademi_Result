package school.chacademia.kyudichi;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;

class RatingStoreSchemaTest {
    @Test void onlyDuplicateColumnErrorsAreIgnored() {
        assertTrue(RatingStore.duplicateColumn(new SQLException("Duplicate column name 'damage'", "42S21", 1060)));
        assertTrue(RatingStore.duplicateColumn(new SQLException("[SQLITE_ERROR] SQL error or missing database (duplicate column name: damage)", null, 1)));
        assertFalse(RatingStore.duplicateColumn(new SQLException("ALTER command denied to user", "42000", 1142)));
        assertFalse(RatingStore.duplicateColumn(new SQLException("Table 'codex_kyudichi_match' doesn't exist", "42S02", 1146)));
        assertFalse(RatingStore.duplicateColumn(new SQLException((String) null)));
    }
}
