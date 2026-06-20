package de.derfakegamer.sentinel.storage;

import de.derfakegamer.sentinel.model.Report;
import org.junit.jupiter.api.*;
import java.io.File;
import java.nio.file.Files;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ReportDaoTest {
    Database db; ReportDao dao; File tmp;

    @BeforeEach void setup() throws Exception {
        tmp = Files.createTempFile("sentinel", ".db").toFile();
        db = new SqliteDatabase(tmp);
        dao = new ReportDao(db);
    }
    @AfterEach void teardown() throws Exception { db.close(); tmp.delete(); }

    private Report open(String reason) {
        return new Report(0, UUID.randomUUID(), "Reporter", UUID.randomUUID(), "Target",
            reason, 100, false, null);
    }

    @Test void insertThenFindOpen() {
        dao.insert(open("hacking"));
        dao.insert(open("spam"));
        assertEquals(2, dao.findOpen().size());
    }

    @Test void markHandledRemovesFromOpen() {
        long id = dao.insert(open("hacking"));
        dao.markHandled(id, "Admin");
        assertEquals(0, dao.findOpen().size());
    }
}
