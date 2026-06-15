package de.derfakegamer.sentinel.storage;

import de.derfakegamer.sentinel.model.OrbitalPayload;
import de.derfakegamer.sentinel.model.ScheduledStrike;
import org.junit.jupiter.api.*;
import java.io.File;
import java.nio.file.Files;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ScheduledStrikeDaoTest {
    Database db; ScheduledStrikeDao dao; File tmp;

    @BeforeEach void setup() throws Exception {
        tmp = Files.createTempFile("sentinel", ".db").toFile();
        db = new Database(tmp);
        dao = new ScheduledStrikeDao(db);
    }
    @AfterEach void teardown() throws Exception { db.close(); tmp.delete(); }

    @Test void insertPendingOrderedThenDelete() {
        long later = dao.insert("world", 10, 20, OrbitalPayload.TNT.name(), 5000L);
        long sooner = dao.insert("world", 1, 2, OrbitalPayload.CHARGED_CREEPER.name(), 1000L);

        List<ScheduledStrike> pending = dao.pending();
        assertEquals(2, pending.size());
        assertEquals(sooner, pending.get(0).id(), "ordered by fire_at ascending");
        assertEquals(later, pending.get(1).id());
        assertEquals("world", pending.get(0).world());
        assertEquals(OrbitalPayload.CHARGED_CREEPER, pending.get(0).payload());

        assertEquals(1, dao.delete(sooner));
        List<ScheduledStrike> remaining = dao.pending();
        assertEquals(1, remaining.size());
        assertEquals(later, remaining.get(0).id());
    }
}
