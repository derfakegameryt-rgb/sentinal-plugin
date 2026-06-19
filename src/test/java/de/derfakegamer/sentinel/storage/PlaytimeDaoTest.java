package de.derfakegamer.sentinel.storage;

import org.junit.jupiter.api.*;
import java.io.File;
import java.nio.file.Files;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class PlaytimeDaoTest {
    Database db; PlayerDao dao; File tmp;
    @BeforeEach void setup() throws Exception {
        tmp = Files.createTempFile("sentinel", ".db").toFile();
        db = new Database(tmp); dao = new PlayerDao(db);
    }
    @AfterEach void teardown() throws Exception { db.close(); tmp.delete(); }

    @Test void accumulatesAndRanks() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        dao.upsert(a, "A", "1.1.1.1", 1);
        dao.upsert(b, "B", "2.2.2.2", 1);
        dao.addPlaytime(a, 1000);
        dao.addPlaytime(a, 4000);   // A total 5000
        dao.addPlaytime(b, 2000);   // B total 2000
        assertEquals(5000, dao.playtime(a));
        assertEquals("A", dao.topByPlaytime(2).get(0).name()); // A ranks first
    }
}
