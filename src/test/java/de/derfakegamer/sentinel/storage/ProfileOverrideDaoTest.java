package de.derfakegamer.sentinel.storage;

import static org.junit.jupiter.api.Assertions.*;

import de.derfakegamer.sentinel.model.ProfileOverride;
import java.io.File;
import java.nio.file.Files;
import java.util.UUID;
import org.junit.jupiter.api.*;

class ProfileOverrideDaoTest {
    Database db;
    ProfileOverrideDao dao;
    File tmp;
    UUID id = UUID.randomUUID();

    @BeforeEach
    void setup() throws Exception {
        tmp = Files.createTempFile("sentinel", ".db").toFile();
        db = new SqliteDatabase(tmp);
        dao = new ProfileOverrideDao(db);
    }

    @AfterEach
    void teardown() throws Exception { db.close(); tmp.delete(); }

    @Test
    void findMissingReturnsNull() { assertNull(dao.find(id)); }

    @Test
    void upsertThenFind() {
        dao.upsert(new ProfileOverride(id, "Cooler", "VAL", "SIG", "Admin", 123L));
        ProfileOverride o = dao.find(id);
        assertNotNull(o);
        assertEquals("Cooler", o.displayName());
        assertEquals("VAL", o.skinValue());
        assertEquals("SIG", o.skinSignature());
        assertEquals("Admin", o.updatedBy());
        assertEquals(123L, o.updatedAt());
    }

    @Test
    void upsertReplacesByUuid() {
        dao.upsert(new ProfileOverride(id, "First", null, null, "A", 1L));
        dao.upsert(new ProfileOverride(id, "Second", "V", "S", "B", 2L));
        ProfileOverride o = dao.find(id);
        assertEquals("Second", o.displayName());
        assertEquals("V", o.skinValue());
    }

    @Test
    void nullableFieldsRoundTrip() {
        dao.upsert(new ProfileOverride(id, null, "V", "S", "A", 1L));
        ProfileOverride o = dao.find(id);
        assertNull(o.displayName());
        assertEquals("V", o.skinValue());
    }

    @Test
    void deleteRemoves() {
        dao.upsert(new ProfileOverride(id, "X", null, null, "A", 1L));
        dao.delete(id);
        assertNull(dao.find(id));
    }
}
