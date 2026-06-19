package de.derfakegamer.sentinel.manager;

import de.derfakegamer.sentinel.Sentinel;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import static org.junit.jupiter.api.Assertions.*;

class OrbitalAccessTest {
    static final java.util.UUID OWNER = java.util.UUID.fromString("6500ca9a-a10c-40a5-b985-a56ca9ff1d1e");
    ServerMock server; Sentinel plugin;
    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    @Test void ownerIsAlwaysAllowed() {
        PlayerMock boss = new PlayerMock(server, "Owner", OWNER);
        server.addPlayer(boss);
        assertTrue(plugin.orbitalAccess().isAllowed(boss));
    }

    @Test void allowlistGrantsAndRevokes() {
        PlayerMock p = server.addPlayer("Helper");
        assertFalse(plugin.orbitalAccess().isAllowed(p));
        plugin.orbitalAccess().add(p.getUniqueId(), "Helper");
        assertTrue(plugin.orbitalAccess().isAllowed(p.getUniqueId()));
        plugin.orbitalAccess().remove(p.getUniqueId());
        assertFalse(plugin.orbitalAccess().isAllowed(p.getUniqueId()));
    }

    @Test void codeDefaultsThenChanges() {
        assertEquals("2584", plugin.orbitalAccess().code());
        plugin.orbitalAccess().setCode("9999");
        assertEquals("9999", plugin.orbitalAccess().code());
    }
}
