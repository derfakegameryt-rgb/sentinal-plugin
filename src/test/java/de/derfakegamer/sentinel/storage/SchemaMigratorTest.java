package de.derfakegamer.sentinel.storage;

import org.junit.jupiter.api.*;
import java.io.File;
import java.nio.file.Files;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;

class SchemaMigratorTest {
    File tmp;
    Database db;

    @BeforeEach
    void setup() throws Exception {
        tmp = Files.createTempFile("sentinel-mig", ".db").toFile();
        db = new SqliteDatabase(tmp);
    }

    @AfterEach
    void teardown() throws Exception {
        db.close();
        tmp.delete();
    }

    private String version() {
        return new SettingsDao(db).get("schema_version", "0");
    }

    private boolean hasPlaytimeColumn() throws Exception {
        try (Statement st = db.connection().createStatement();
             ResultSet rs = st.executeQuery("SELECT playtime FROM players LIMIT 0")) {
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    @Test
    void freshDbEndsAtLatestVersion() {
        // SqliteDatabase ctor already ran baseline + migrator; version must be the latest.
        assertEquals(String.valueOf(SchemaMigrator.latestVersion()), version());
    }

    @Test
    void playtimeColumnExistsAfterMigration() throws Exception {
        assertTrue(hasPlaytimeColumn());
    }

    @Test
    void reRunningMigratorIsNoOpAndKeepsVersion() throws Exception {
        SchemaMigrator.migrate(db, java.util.logging.Logger.getLogger("test")); // re-run
        assertEquals(String.valueOf(SchemaMigrator.latestVersion()), version());
        assertTrue(hasPlaytimeColumn());
    }

    @Test
    void migratorReappliesIdempotentlyFromVersionZero() throws Exception {
        new SettingsDao(db).set("schema_version", "0"); // pretend legacy DB
        SchemaMigrator.migrate(db, java.util.logging.Logger.getLogger("test")); // V1 re-runs, column already exists -> swallowed
        assertEquals(String.valueOf(SchemaMigrator.latestVersion()), version());
        assertTrue(hasPlaytimeColumn());
    }
}
