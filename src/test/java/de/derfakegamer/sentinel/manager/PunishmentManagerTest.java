package de.derfakegamer.sentinel.manager;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.storage.PunishmentDao;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class PunishmentManagerTest {
    ServerMock server;
    Sentinel plugin;
    PunishmentManager mgr;
    UUID target = UUID.randomUUID();
    UUID issuer = UUID.randomUUID();

    @BeforeEach void setup() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(Sentinel.class);
        mgr = plugin.punishments();
    }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    @Test void banThenActiveBanFound() throws Exception {
        assertTrue(mgr.ban(target, "Notch", issuer, "Admin", "hax", 0).get(2, TimeUnit.SECONDS).isSuccess());
        assertNotNull(mgr.activeBan(target, System.currentTimeMillis()).get(2, TimeUnit.SECONDS));
    }

    @Test void exemptCannotBeBanned() throws Exception {
        UUID exemptId = UUID.randomUUID();
        // Construct a manager with this UUID in the exempt set, sharing the plugin's executor and DB
        PunishmentManager pm2 = new PunishmentManager(plugin, new PunishmentDao(plugin.db().database()), Set.of(exemptId));
        var result = pm2.ban(exemptId, "Owner", issuer, "Admin", "x", 0).get(2, TimeUnit.SECONDS);
        assertFalse(result.isSuccess());
        assertNull(pm2.activeBan(exemptId, System.currentTimeMillis()).get(2, TimeUnit.SECONDS));
    }

    @Test void expiredBanReturnsNullAndIsDeactivated() throws Exception {
        long now = 1_000_000L;
        mgr.ban(target, "Notch", issuer, "Admin", "hax", now + 1000).get(2, TimeUnit.SECONDS);
        assertNull(mgr.activeBan(target, now + 2000).get(2, TimeUnit.SECONDS));
        // second lookup confirms it was deactivated, not just filtered
        assertNull(mgr.activeBan(target, now + 1).get(2, TimeUnit.SECONDS));
    }

    @Test void unbanClearsBan() throws Exception {
        mgr.ban(target, "Notch", issuer, "Admin", "hax", 0).get(2, TimeUnit.SECONDS);
        assertTrue(mgr.unban(target, "Admin", System.currentTimeMillis()).get(2, TimeUnit.SECONDS));
        assertNull(mgr.activeBan(target, System.currentTimeMillis()).get(2, TimeUnit.SECONDS));
    }

    @Test void warnIncrementsCount() throws Exception {
        mgr.warn(target, "Notch", issuer, "Admin", "spam").get(2, TimeUnit.SECONDS);
        mgr.warn(target, "Notch", issuer, "Admin", "spam").get(2, TimeUnit.SECONDS);
        assertEquals(2, mgr.warnCount(target).get(2, TimeUnit.SECONDS));
    }
}
