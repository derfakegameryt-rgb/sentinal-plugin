package de.derfakegamer.sentinel.manager;

import de.derfakegamer.sentinel.model.Punishment;
import de.derfakegamer.sentinel.storage.Database;
import de.derfakegamer.sentinel.storage.PunishmentDao;
import org.junit.jupiter.api.*;
import java.io.File;
import java.nio.file.Files;
import java.util.Set;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class PunishmentManagerTest {
    Database db; PunishmentManager mgr; File tmp;
    UUID target = UUID.randomUUID(); UUID issuer = UUID.randomUUID();
    UUID exempt = UUID.randomUUID();

    @BeforeEach void setup() throws Exception {
        tmp = Files.createTempFile("sentinel", ".db").toFile();
        db = new Database(tmp);
        mgr = new PunishmentManager(new PunishmentDao(db), Set.of(exempt));
    }
    @AfterEach void teardown() throws Exception { db.close(); tmp.delete(); }

    @Test void banThenActiveBanFound() {
        assertTrue(mgr.ban(target, "Notch", issuer, "Admin", "hax", 0).isSuccess());
        assertNotNull(mgr.activeBan(target, System.currentTimeMillis()));
    }

    @Test void exemptCannotBeBanned() {
        var result = mgr.ban(exempt, "Owner", issuer, "Admin", "x", 0);
        assertFalse(result.isSuccess());
        assertNull(mgr.activeBan(exempt, System.currentTimeMillis()));
    }

    @Test void expiredBanReturnsNullAndIsDeactivated() {
        long now = 1_000_000L;
        mgr.ban(target, "Notch", issuer, "Admin", "hax", now + 1000); // expires soon
        assertNull(mgr.activeBan(target, now + 2000));                 // past expiry
        // second lookup confirms it was deactivated, not just filtered
        assertNull(mgr.activeBan(target, now + 1));
    }

    @Test void unbanClearsBan() {
        mgr.ban(target, "Notch", issuer, "Admin", "hax", 0);
        assertTrue(mgr.unban(target, "Admin", System.currentTimeMillis()));
        assertNull(mgr.activeBan(target, System.currentTimeMillis()));
    }

    @Test void warnIncrementsCount() {
        mgr.warn(target, "Notch", issuer, "Admin", "spam");
        mgr.warn(target, "Notch", issuer, "Admin", "spam");
        assertEquals(2, mgr.warnCount(target));
    }
}
