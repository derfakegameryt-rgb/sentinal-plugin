package de.derfakegamer.sentinel.storage;

import de.derfakegamer.sentinel.model.PlayerRecord;
import org.junit.jupiter.api.*;
import java.io.File;
import java.nio.file.Files;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class PlayerDaoTest {
    Database db; PlayerDao dao; File tmp;

    @BeforeEach void setup() throws Exception {
        tmp = Files.createTempFile("sentinel", ".db").toFile();
        db = new SqliteDatabase(tmp);
        dao = new PlayerDao(db);
    }
    @AfterEach void teardown() throws Exception { db.close(); tmp.delete(); }

    @Test void upsertInsertsThenUpdates() {
        UUID id = UUID.randomUUID();
        dao.upsert(id, "Steve", "1.2.3.4", 100);
        dao.upsert(id, "SteveRenamed", "5.6.7.8", 200);
        PlayerRecord r = dao.byUuid(id);
        assertNotNull(r);
        assertEquals("SteveRenamed", r.name());
        assertEquals("5.6.7.8", r.lastIp());
        assertEquals(100, r.firstSeen());   // first_seen preserved
        assertEquals(200, r.lastSeen());
    }

    @Test void byNameIsCaseInsensitive() {
        UUID id = UUID.randomUUID();
        dao.upsert(id, "Alex", "1.1.1.1", 100);
        assertEquals(id, dao.byName("alex").uuid());
    }

    @Test void byIpReturnsAllSharingThatIp() {
        dao.upsert(UUID.randomUUID(), "A", "9.9.9.9", 100);
        dao.upsert(UUID.randomUUID(), "B", "9.9.9.9", 100);
        dao.upsert(UUID.randomUUID(), "C", "8.8.8.8", 100);
        assertEquals(2, dao.byIp("9.9.9.9").size());
    }
}
