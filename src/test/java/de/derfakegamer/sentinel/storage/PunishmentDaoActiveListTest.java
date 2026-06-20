package de.derfakegamer.sentinel.storage;

import de.derfakegamer.sentinel.model.Punishment;
import de.derfakegamer.sentinel.model.PunishmentType;
import org.junit.jupiter.api.*;
import java.io.File;
import java.nio.file.Files;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class PunishmentDaoActiveListTest {
    Database db; PunishmentDao dao; File tmp;

    @BeforeEach void setup() throws Exception {
        tmp = Files.createTempFile("sentinel", ".db").toFile();
        db = new SqliteDatabase(tmp);
        dao = new PunishmentDao(db);
    }
    @AfterEach void teardown() throws Exception { db.close(); tmp.delete(); }

    private Punishment ban(String name) {
        return Punishment.builder().type(PunishmentType.BAN).targetUuid(UUID.randomUUID())
            .targetName(name).reason("x").issuerUuid(UUID.randomUUID()).issuerName("Admin")
            .createdAt(100).expiresAt(0).active(true).build();
    }

    @Test void findActiveByTypeReturnsOnlyActiveOfThatType() {
        dao.insert(ban("A"));
        long id = dao.insert(ban("B"));
        dao.insert(Punishment.builder().type(PunishmentType.MUTE).targetUuid(UUID.randomUUID())
            .targetName("M").reason("x").issuerUuid(UUID.randomUUID()).issuerName("Admin")
            .createdAt(100).expiresAt(0).active(true).build());
        dao.deactivate(id, "Admin", 200);
        assertEquals(1, dao.findActiveByType(PunishmentType.BAN).size()); // only "A" (B deactivated)
        assertEquals(1, dao.findActiveByType(PunishmentType.MUTE).size());
    }
}
