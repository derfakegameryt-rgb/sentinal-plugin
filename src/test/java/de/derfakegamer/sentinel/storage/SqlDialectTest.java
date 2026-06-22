package de.derfakegamer.sentinel.storage;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SqlDialectTest {
    private static String schema(SqlDialect d) { return String.join("\n", d.schemaStatements()); }

    private static final String[] TABLES =
        {"punishments", "reports", "appeals", "players", "notes", "chatlog", "settings", "audit"};

    @Test void sqliteSchemaHasAllTablesAndSqliteTypes() {
        String s = schema(SqlDialect.SQLITE);
        for (String t : TABLES) assertTrue(s.contains("CREATE TABLE IF NOT EXISTS " + t), "missing " + t);
        assertTrue(s.contains("AUTOINCREMENT"));
        assertTrue(s.contains("COLLATE NOCASE"));
        assertFalse(s.contains("AUTO_INCREMENT"));
    }

    @Test void upsertsAreSqliteCorrect() {
        assertTrue(SqlDialect.SQLITE.playersUpsert().contains("ON CONFLICT(uuid) DO UPDATE"));
        assertTrue(SqlDialect.SQLITE.settingsUpsert().contains("ON CONFLICT(key) DO UPDATE"));
    }

    @Test void nameCollateForSqlite() {
        assertEquals(" COLLATE NOCASE", SqlDialect.SQLITE.nameWhereCollate());
    }

    @Test void compositeIndexesPresent() {
        String s = schema(SqlDialect.SQLITE);
        assertTrue(s.contains("idx_pun_active"), "missing idx_pun_active");
        assertTrue(s.contains("idx_pun_type_active"), "missing idx_pun_type_active");
    }
}
