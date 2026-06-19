package de.derfakegamer.sentinel.manager;

import de.derfakegamer.sentinel.Sentinel;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ReportManagerTest {
    ServerMock server; Sentinel plugin;

    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    @Test void fileCreatesOpenReport() throws Exception {
        PlayerMock reporter = server.addPlayer("Reporter");
        PlayerMock target = server.addPlayer("Target");
        boolean ok = plugin.reports().file(reporter, target.getUniqueId(), target.getName(), "hacking")
                .get(2, TimeUnit.SECONDS);
        assertTrue(ok);
        assertEquals(1, plugin.reports().open().get(2, TimeUnit.SECONDS).size());
    }

    @Test void handleClosesReport() throws Exception {
        PlayerMock reporter = server.addPlayer("Reporter");
        PlayerMock target = server.addPlayer("Target");
        plugin.reports().file(reporter, target.getUniqueId(), target.getName(), "hacking")
                .get(2, TimeUnit.SECONDS);
        long id = plugin.reports().open().get(2, TimeUnit.SECONDS).get(0).id();
        plugin.reports().handle(id, "Admin");
        // handle is fire-and-forget; drain the DB executor via a no-op submit
        plugin.db().submit(() -> null).get(2, TimeUnit.SECONDS);
        assertEquals(0, plugin.reports().open().get(2, TimeUnit.SECONDS).size());
    }
}
