package de.derfakegamer.sentinel.storage;

import de.derfakegamer.sentinel.model.Note;
import org.junit.jupiter.api.*;
import java.io.File;
import java.nio.file.Files;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class NoteDaoTest {
    Database db; NoteDao dao; File tmp;
    UUID target = UUID.randomUUID();

    @BeforeEach void setup() throws Exception {
        tmp = Files.createTempFile("sentinel", ".db").toFile();
        db = new Database(tmp);
        dao = new NoteDao(db);
    }
    @AfterEach void teardown() throws Exception { db.close(); tmp.delete(); }

    @Test void insertThenList() {
        dao.insert(new Note(0, target, "Admin", "watch this guy", 100));
        dao.insert(new Note(0, target, "Mod", "spammed once", 200));
        assertEquals(2, dao.listFor(target).size());
    }

    @Test void listForUnknownIsEmpty() {
        assertTrue(dao.listFor(UUID.randomUUID()).isEmpty());
    }
}
