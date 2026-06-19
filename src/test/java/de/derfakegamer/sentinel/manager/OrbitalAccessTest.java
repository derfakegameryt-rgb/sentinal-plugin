package de.derfakegamer.sentinel.manager;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.storage.OrbitalAllowDao;
import de.derfakegamer.sentinel.storage.SettingsDao;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class OrbitalAccessTest {
    static final UUID OWNER = UUID.fromString("6500ca9a-a10c-40a5-b985-a56ca9ff1d1e");
    ServerMock server;
    Sentinel plugin;

    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    /** Drain the DB executor by submitting a no-op and waiting. */
    private void drainDb() throws Exception {
        plugin.db().submit(() -> null).get(2, TimeUnit.SECONDS);
    }

    @Test void ownerIsAlwaysAllowed() {
        PlayerMock boss = new PlayerMock(server, "Owner", OWNER);
        server.addPlayer(boss);
        assertTrue(plugin.orbitalAccess().isAllowed(boss));
    }

    @Test void allowlistGrantsAndRevokes() {
        PlayerMock p = server.addPlayer("Helper");
        assertFalse(plugin.orbitalAccess().isAllowed(p));

        // add() updates cache synchronously — isAllowed must be true immediately
        plugin.orbitalAccess().add(p.getUniqueId(), "Helper");
        assertTrue(plugin.orbitalAccess().isAllowed(p.getUniqueId()),
                "isAllowed should be true immediately after add (cache)");
        assertTrue(plugin.orbitalAccess().isAllowed(p),
                "isAllowed(Player) should be true immediately after add");

        plugin.orbitalAccess().remove(p.getUniqueId());
        assertFalse(plugin.orbitalAccess().isAllowed(p.getUniqueId()),
                "isAllowed should be false immediately after remove (cache)");
    }

    @Test void addPersistsToDb() throws Exception {
        PlayerMock p = server.addPlayer("PersistPlayer");
        UUID id = p.getUniqueId();

        plugin.orbitalAccess().add(id, "PersistPlayer");

        // drain executor so the async write completes
        drainDb();

        // build a fresh OrbitalAccess on the same DB — it must see the persisted entry
        OrbitalAccess fresh = new OrbitalAccess(plugin,
                new SettingsDao(plugin.db().database()),
                new OrbitalAllowDao(plugin.db().database()));
        assertTrue(fresh.isAllowed(id),
                "freshly-constructed OrbitalAccess must see persisted entry");
    }

    @Test void removePersistsToDb() throws Exception {
        PlayerMock p = server.addPlayer("RmPlayer");
        UUID id = p.getUniqueId();

        plugin.orbitalAccess().add(id, "RmPlayer");
        drainDb();
        plugin.orbitalAccess().remove(id);
        drainDb();

        OrbitalAccess fresh = new OrbitalAccess(plugin,
                new SettingsDao(plugin.db().database()),
                new OrbitalAllowDao(plugin.db().database()));
        assertFalse(fresh.isAllowed(id),
                "freshly-constructed OrbitalAccess must not see removed entry");
    }

    @Test void codeDefaultsThenChanges() throws Exception {
        assertEquals("2584", plugin.orbitalAccess().code());

        plugin.orbitalAccess().setCode("9999");
        // cache update is immediate
        assertEquals("9999", plugin.orbitalAccess().code());

        // drain and verify persistence
        drainDb();

        OrbitalAccess fresh = new OrbitalAccess(plugin,
                new SettingsDao(plugin.db().database()),
                new OrbitalAllowDao(plugin.db().database()));
        assertEquals("9999", fresh.code(), "fresh OrbitalAccess must see persisted code");
    }

    @Test void listReturnsCopyOfCache() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        plugin.orbitalAccess().add(id1, "Alice");
        plugin.orbitalAccess().add(id2, "Bob");

        var map = plugin.orbitalAccess().list();
        assertTrue(map.containsKey(id1));
        assertTrue(map.containsKey(id2));

        // modifying the returned map must not affect the cache
        map.clear();
        assertTrue(plugin.orbitalAccess().isAllowed(id1), "cache must be unaffected by list() mutation");
    }
}
