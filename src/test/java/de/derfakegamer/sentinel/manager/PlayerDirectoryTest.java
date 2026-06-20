package de.derfakegamer.sentinel.manager;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.PlayerRecord;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class PlayerDirectoryTest {
    ServerMock server; Sentinel plugin;

    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    @Test void recordThenLookupByName() throws Exception {
        UUID id = UUID.randomUUID();
        plugin.players().record(id, "Notch", "1.2.3.4");
        assertEquals(id, plugin.players().byName("notch").get(2, TimeUnit.SECONDS).uuid());
        assertEquals("1.2.3.4", plugin.players().byUuid(id).get(2, TimeUnit.SECONDS).lastIp());
    }

    @Test void altsShareIpExcludingSelf() throws Exception {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        plugin.players().record(a, "Main", "5.5.5.5");
        plugin.players().record(b, "Alt", "5.5.5.5");
        List<PlayerRecord> alts =
            plugin.players().alts(a).get(2, TimeUnit.SECONDS);
        assertEquals(1, alts.size());
        assertEquals(b, alts.get(0).uuid());
    }

    @Test void byUuidServesFromOnlineCache() throws Exception {
        UUID id = UUID.randomUUID();
        long now = System.currentTimeMillis();
        PlayerRecord rec = new PlayerRecord(id, "Bob", "1.2.3.4", now, now, 0);
        plugin.players().cacheOnline(rec);
        // No DB row was inserted for this UUID — cache must serve the record
        PlayerRecord found = plugin.players().byUuid(id).get(2, TimeUnit.SECONDS);
        assertEquals("Bob", found.name());
        // After eviction, falls back to DB (no row → null)
        plugin.players().evict(id, "Bob");
        assertNull(plugin.players().byUuid(id).get(2, TimeUnit.SECONDS));
    }

    @Test void byNameServesFromOnlineCache() throws Exception {
        UUID id = UUID.randomUUID();
        long now = System.currentTimeMillis();
        PlayerRecord rec = new PlayerRecord(id, "Alice", "9.9.9.9", now, now, 0);
        plugin.players().cacheOnline(rec);
        // Lookup by lower-cased name
        PlayerRecord found = plugin.players().byName("alice").get(2, TimeUnit.SECONDS);
        assertEquals(id, found.uuid());
        // After eviction, falls back to DB (no row → null)
        plugin.players().evict(id, "Alice");
        assertNull(plugin.players().byName("alice").get(2, TimeUnit.SECONDS));
    }
}
