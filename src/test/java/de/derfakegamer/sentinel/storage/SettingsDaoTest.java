package de.derfakegamer.sentinel.storage;

import org.junit.jupiter.api.*;
import java.io.File;
import java.nio.file.Files;
import static org.junit.jupiter.api.Assertions.*;

class SettingsDaoTest {
    Database db; SettingsDao dao; File tmp;

    @BeforeEach void setup() throws Exception {
        tmp = Files.createTempFile("sentinel", ".db").toFile();
        db = new Database(tmp);
        dao = new SettingsDao(db);
    }
    @AfterEach void teardown() throws Exception { db.close(); tmp.delete(); }

    @Test void getReturnsDefaultWhenMissing() {
        assertEquals("fallback", dao.get("missing", "fallback"));
    }

    @Test void setThenGet() {
        dao.set("k", "v");
        assertEquals("v", dao.get("k", "def"));
    }

    @Test void upsertOverwrites() {
        dao.set("k", "first");
        dao.set("k", "second");
        assertEquals("second", dao.get("k", "def"));
    }
}
