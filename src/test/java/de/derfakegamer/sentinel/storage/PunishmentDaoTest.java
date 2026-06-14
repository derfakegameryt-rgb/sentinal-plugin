package de.derfakegamer.sentinel.storage;

import de.derfakegamer.sentinel.model.Punishment;
import de.derfakegamer.sentinel.model.PunishmentType;
import org.junit.jupiter.api.*;
import java.io.File;
import java.nio.file.Files;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class PunishmentDaoTest {
    Database db; PunishmentDao dao; File tmp;
    UUID target = UUID.randomUUID(); UUID issuer = UUID.randomUUID();

    @BeforeEach void setup() throws Exception {
        tmp = Files.createTempFile("sentinel", ".db").toFile();
        db = new Database(tmp);
        dao = new PunishmentDao(db);
    }
    @AfterEach void teardown() throws Exception { db.close(); tmp.delete(); }

    private Punishment ban(long expiresAt) {
        return Punishment.builder().type(PunishmentType.BAN).targetUuid(target)
            .targetName("Notch").reason("hax").issuerUuid(issuer).issuerName("Admin")
            .createdAt(100).expiresAt(expiresAt).active(true).build();
    }

    @Test void insertAndFindActive() {
        long id = dao.insert(ban(0));
        assertTrue(id > 0);
        Punishment found = dao.findActive(PunishmentType.BAN, target);
        assertNotNull(found);
        assertEquals("hax", found.reason());
    }

    @Test void deactivateRemovesActive() {
        long id = dao.insert(ban(0));
        dao.deactivate(id, "Admin", 200);
        assertNull(dao.findActive(PunishmentType.BAN, target));
    }

    @Test void historyReturnsAll() {
        dao.insert(ban(0));
        dao.insert(ban(0));
        assertEquals(2, dao.findHistory(target).size());
    }

    @Test void countWarnsCountsActiveWarns() {
        dao.insert(Punishment.builder().type(PunishmentType.WARN).targetUuid(target)
            .targetName("Notch").reason("spam").issuerUuid(issuer).issuerName("Admin")
            .createdAt(100).expiresAt(0).active(true).build());
        assertEquals(1, dao.countWarns(target));
    }
}
