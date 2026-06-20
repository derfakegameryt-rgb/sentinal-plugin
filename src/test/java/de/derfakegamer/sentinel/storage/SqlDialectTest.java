package de.derfakegamer.sentinel.storage;

import org.junit.jupiter.api.Test;
import java.util.List;
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

    @Test void mysqlSchemaHasAllTablesAndMysqlTypes() {
        String s = schema(SqlDialect.MYSQL);
        for (String t : TABLES) assertTrue(s.contains("CREATE TABLE IF NOT EXISTS " + t), "missing " + t);
        assertTrue(s.contains("AUTO_INCREMENT"));
        assertTrue(s.contains("VARCHAR"));                 // keyed/indexed strings are VARCHAR, not TEXT
        assertFalse(s.contains("AUTOINCREMENT"));          // the SQLite keyword must not appear
        assertFalse(s.toUpperCase().contains("PRAGMA"));
        assertTrue(s.contains("`key`") && s.contains("`value`")); // reserved words are backticked
        assertTrue(s.contains("COLLATE utf8mb4_general_ci"), "players.name must be explicitly case-insensitive via utf8mb4_general_ci");
    }

    @Test void upsertsAreDialectCorrect() {
        assertTrue(SqlDialect.SQLITE.playersUpsert().contains("ON CONFLICT(uuid) DO UPDATE"));
        assertTrue(SqlDialect.MYSQL.playersUpsert().contains("ON DUPLICATE KEY UPDATE"));
        assertTrue(SqlDialect.SQLITE.settingsUpsert().contains("ON CONFLICT(key) DO UPDATE"));
        assertTrue(SqlDialect.MYSQL.settingsUpsert().contains("ON DUPLICATE KEY UPDATE"));
        assertTrue(SqlDialect.MYSQL.settingsUpsert().contains("`value`=VALUES(`value`)"));
    }

    @Test void auditTablePresentInBothDialects() {
        assertTrue(String.join("\n", SqlDialect.SQLITE.schemaStatements()).contains("CREATE TABLE IF NOT EXISTS audit"));
        assertTrue(String.join("\n", SqlDialect.MYSQL.schemaStatements()).contains("CREATE TABLE IF NOT EXISTS audit"));
    }

    @Test void nameCollateOnlyForSqlite() {
        assertEquals(" COLLATE NOCASE", SqlDialect.SQLITE.nameWhereCollate());
        assertEquals("", SqlDialect.MYSQL.nameWhereCollate());
    }
}
