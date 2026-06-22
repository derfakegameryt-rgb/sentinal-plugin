package de.derfakegamer.sentinel.manager;

import de.derfakegamer.sentinel.Sentinel;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import static org.junit.jupiter.api.Assertions.*;

class AutoAnnouncerTest {
    ServerMock server; Sentinel plugin;
    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    @Test void cyclesThroughMessages() {
        plugin.getConfig().set("announcements.messages", java.util.List.of("one", "two"));
        AutoAnnouncer a = new AutoAnnouncer(plugin);
        assertEquals("one", a.announceNext());
        assertEquals("two", a.announceNext());
        assertEquals("one", a.announceNext()); // round-robin
    }

    @Test void emptyMessagesIsNoOp() {
        plugin.getConfig().set("announcements.messages", java.util.List.of());
        assertNull(new AutoAnnouncer(plugin).announceNext());
    }

    @Test void setEnabledTogglesAndPersists() {
        AutoAnnouncer a = new AutoAnnouncer(plugin);
        a.setEnabled(false);
        assertFalse(a.isEnabled());
        assertFalse(plugin.getConfig().getBoolean("announcements.enabled"));
        a.setEnabled(true);
        assertTrue(a.isEnabled());
        assertTrue(plugin.getConfig().getBoolean("announcements.enabled"));
    }
}
