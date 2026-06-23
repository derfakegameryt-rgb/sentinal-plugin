package de.derfakegamer.sentinel.storage;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.file.Files;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.*;

class ProfileOverrideSchemaTest {
    Database db;
    File tmp;

    @BeforeEach
    void setup() throws Exception {
        tmp = Files.createTempFile("sentinel", ".db").toFile();
        db = new SqliteDatabase(tmp);
    }

    @AfterEach
    void teardown() throws Exception { db.close(); tmp.delete(); }

    @Test
    void profileOverridesTableExists() throws Exception {
        try (Statement st = db.connection().createStatement();
             ResultSet rs = st.executeQuery(
                 "SELECT name FROM sqlite_master WHERE type='table' AND name='profile_overrides'")) {
            assertTrue(rs.next(), "profile_overrides table should be created on startup");
        }
    }
}
