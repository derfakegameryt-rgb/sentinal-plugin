package de.derfakegamer.sentinel.storage;

import de.derfakegamer.sentinel.model.Appeal;
import de.derfakegamer.sentinel.model.PunishmentType;
import org.junit.jupiter.api.*;
import java.io.File;
import java.nio.file.Files;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class AppealDaoTest {
    Database db; AppealDao dao; File tmp;

    @BeforeEach void setup() throws Exception {
        tmp = Files.createTempFile("sentinel", ".db").toFile();
        db = new Database(tmp);
        dao = new AppealDao(db);
    }
    @AfterEach void teardown() throws Exception { db.close(); tmp.delete(); }

    private Appeal open(UUID target, String text) {
        return new Appeal(0, 7, target, "Target", PunishmentType.MUTE, text, "OPEN", 100, null, 0);
    }

    @Test void insertThenFindOpen() {
        UUID target = UUID.randomUUID();
        dao.insert(open(target, "please unmute me"));
        assertEquals(1, dao.findOpen().size());
        assertTrue(dao.hasOpenForTarget(target));
    }

    @Test void setStatusAcceptedRemovesFromOpen() {
        UUID target = UUID.randomUUID();
        long id = dao.insert(open(target, "sorry"));
        dao.setStatus(id, "ACCEPTED", "Admin", 200);
        assertEquals(0, dao.findOpen().size());
        assertFalse(dao.hasOpenForTarget(target));
        Appeal a = dao.byId(id);
        assertNotNull(a);
        assertEquals("ACCEPTED", a.status());
        assertEquals("Admin", a.handledBy());
    }
}
