package de.derfakegamer.sentinel.manager;

import de.derfakegamer.sentinel.Sentinel;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import static org.junit.jupiter.api.Assertions.*;

class ReportManagerTest {
    ServerMock server; Sentinel plugin;

    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    @Test void fileCreatesOpenReport() {
        PlayerMock reporter = server.addPlayer("Reporter");
        PlayerMock target = server.addPlayer("Target");
        boolean ok = plugin.reports().file(reporter, target.getUniqueId(), target.getName(), "hacking");
        assertTrue(ok);
        assertEquals(1, plugin.reports().open().size());
    }

    @Test void handleClosesReport() {
        PlayerMock reporter = server.addPlayer("Reporter");
        PlayerMock target = server.addPlayer("Target");
        plugin.reports().file(reporter, target.getUniqueId(), target.getName(), "hacking");
        long id = plugin.reports().open().get(0).id();
        plugin.reports().handle(id, "Admin");
        assertEquals(0, plugin.reports().open().size());
    }
}
