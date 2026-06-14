package de.derfakegamer.sentinel.manager;

import de.derfakegamer.sentinel.Sentinel;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class PlayerDirectoryTest {
    ServerMock server; Sentinel plugin;

    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    @Test void recordThenLookupByName() {
        UUID id = UUID.randomUUID();
        plugin.players().record(id, "Notch", "1.2.3.4");
        assertEquals(id, plugin.players().byName("notch").uuid());
        assertEquals("1.2.3.4", plugin.players().byUuid(id).lastIp());
    }

    @Test void altsShareIpExcludingSelf() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        plugin.players().record(a, "Main", "5.5.5.5");
        plugin.players().record(b, "Alt", "5.5.5.5");
        var alts = plugin.players().alts(a);
        assertEquals(1, alts.size());
        assertEquals(b, alts.get(0).uuid());
    }
}
