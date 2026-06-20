package de.derfakegamer.sentinel.manager;

import de.derfakegamer.sentinel.Sentinel;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class AuditManagerTest {
    ServerMock server; Sentinel plugin;
    @BeforeEach void setUp() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void tearDown() { MockBukkit.unmock(); }

    @Test void recordThenRecent() throws Exception {
        plugin.audit().record("Mod", "BAN", "Bob", "spam");
        // drain the single-thread executor, then read
        var all = plugin.audit().recent(10, 0).get(2, TimeUnit.SECONDS);
        assertEquals(1, all.size());
        assertEquals("Mod", all.get(0).actor());
        assertEquals("BAN", all.get(0).action());
        assertEquals("Bob", all.get(0).target());
    }

    @Test void topActorsAggregates() throws Exception {
        plugin.audit().record("A", "BAN", "x", "");
        plugin.audit().record("A", "KICK", "y", "");
        plugin.audit().record("B", "WARN", "z", "");
        var top = plugin.audit().topActors(0, 10).get(2, TimeUnit.SECONDS);
        assertEquals("A", top.get(0).actor());
        assertEquals(2, top.get(0).count());
    }
}
