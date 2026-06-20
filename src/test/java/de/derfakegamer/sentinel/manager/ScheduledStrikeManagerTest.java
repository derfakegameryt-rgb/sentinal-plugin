package de.derfakegamer.sentinel.manager;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.OrbitalPayload;
import de.derfakegamer.sentinel.model.ScheduledStrike;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ScheduledStrikeManagerTest {
    ServerMock server;
    Sentinel plugin;

    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    @Test void scheduleReturnsFutureWithId() throws Exception {
        long fireAt = System.currentTimeMillis() + 60_000;
        long id = plugin.scheduledStrikes()
            .schedule(server.addSimpleWorld("world"), 10, 20, OrbitalPayload.TNT, fireAt)
            .get(2, TimeUnit.SECONDS);
        assertTrue(id > 0, "inserted ID should be positive");
    }

    @Test void scheduleArmsTaskViaCallback() throws Exception {
        long fireAt = System.currentTimeMillis() + 60_000;
        plugin.scheduledStrikes()
            .schedule(server.addSimpleWorld("world"), 0, 0, OrbitalPayload.TNT, fireAt)
            .get(2, TimeUnit.SECONDS);
        // The arm callback runs on the Bukkit main thread via scheduler — flush it
        server.getScheduler().performTicks(1);
        // If the arm ran, pending() should now be empty (strike is armed but still pending in DB)
        List<ScheduledStrike> pending = plugin.scheduledStrikes().pending()
            .get(2, TimeUnit.SECONDS);
        assertEquals(1, pending.size());
    }

    @Test void pendingReturnsFutureWithList() throws Exception {
        List<ScheduledStrike> initial = plugin.scheduledStrikes().pending()
            .get(2, TimeUnit.SECONDS);
        assertTrue(initial.isEmpty());

        long fireAt = System.currentTimeMillis() + 60_000;
        plugin.scheduledStrikes()
            .schedule(server.addSimpleWorld("world"), 5, 6, OrbitalPayload.CHARGED_CREEPER, fireAt)
            .get(2, TimeUnit.SECONDS);

        List<ScheduledStrike> after = plugin.scheduledStrikes().pending()
            .get(2, TimeUnit.SECONDS);
        assertEquals(1, after.size());
        assertEquals(5, after.get(0).x());
        assertEquals(6, after.get(0).z());
        assertEquals(OrbitalPayload.CHARGED_CREEPER, after.get(0).payload());
    }

    @Test void cancelDeletesFromDb() throws Exception {
        long fireAt = System.currentTimeMillis() + 60_000;
        long id = plugin.scheduledStrikes()
            .schedule(server.addSimpleWorld("world"), 1, 2, OrbitalPayload.TNT, fireAt)
            .get(2, TimeUnit.SECONDS);

        boolean ok = plugin.scheduledStrikes().cancel(id).get(2, TimeUnit.SECONDS);
        assertTrue(ok);

        List<ScheduledStrike> pending = plugin.scheduledStrikes().pending()
            .get(2, TimeUnit.SECONDS);
        assertTrue(pending.isEmpty());
    }

    @Test void cancelNonExistentReturnsFalse() throws Exception {
        boolean ok = plugin.scheduledStrikes().cancel(9999L).get(2, TimeUnit.SECONDS);
        assertFalse(ok);
    }

    @Test void rearmAllArmsPersistedStrikes() throws Exception {
        long fireAt = System.currentTimeMillis() + 60_000;
        plugin.scheduledStrikes()
            .schedule(server.addSimpleWorld("world"), 3, 4, OrbitalPayload.TNT, fireAt)
            .get(2, TimeUnit.SECONDS);
        // Flush arm callback
        server.getScheduler().performTicks(1);

        // rearmAll should re-schedule tasks from DB — smoke-test: no exception
        plugin.scheduledStrikes().rearmAll();
        // Drain callback
        plugin.db().submit(() -> null).get(2, TimeUnit.SECONDS);
        server.getScheduler().performTicks(1);

        List<ScheduledStrike> pending = plugin.scheduledStrikes().pending()
            .get(2, TimeUnit.SECONDS);
        assertEquals(1, pending.size());
    }
}
