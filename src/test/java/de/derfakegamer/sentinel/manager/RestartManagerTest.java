package de.derfakegamer.sentinel.manager;

import de.derfakegamer.sentinel.Sentinel;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import static org.junit.jupiter.api.Assertions.*;

class RestartManagerTest {
    ServerMock server; Sentinel plugin;
    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    @Test void warnTicksAtKnownMarks() {
        RestartManager r = new RestartManager(plugin);
        assertTrue(r.isWarnTick(60));
        assertTrue(r.isWarnTick(5));
        assertFalse(r.isWarnTick(42));
    }

    @Test void humanFormat() {
        assertEquals("5s", RestartManager.human(5));
        assertEquals("2m 5s", RestartManager.human(125));
    }
}
