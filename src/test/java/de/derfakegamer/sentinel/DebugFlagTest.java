package de.derfakegamer.sentinel;

import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.*;

import static org.junit.jupiter.api.Assertions.*;

class DebugFlagTest {
    ServerMock server;
    Sentinel plugin;

    @BeforeEach
    void setup() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(Sentinel.class);
    }

    @AfterEach
    void teardown() {
        MockBukkit.unmock();
    }

    private AtomicInteger countDebugRecords() {
        AtomicInteger n = new AtomicInteger();
        plugin.getLogger().addHandler(new Handler() {
            public void publish(LogRecord r) {
                if (r.getMessage() != null && r.getMessage().contains("[DEBUG]")) n.incrementAndGet();
            }
            public void flush() {}
            public void close() {}
        });
        return n;
    }

    @Test
    void debugSuppressedWhenOff() {
        plugin.getConfig().set("debug", false);
        plugin.reloadDebugFlag();
        AtomicInteger n = countDebugRecords();
        plugin.debug("hello");
        assertEquals(0, n.get());
        assertFalse(plugin.debug());
    }

    @Test
    void debugLoggedWhenOn() {
        plugin.getConfig().set("debug", true);
        plugin.reloadDebugFlag();
        AtomicInteger n = countDebugRecords();
        plugin.debug("hello");
        assertEquals(1, n.get());
        assertTrue(plugin.debug());
    }
}
