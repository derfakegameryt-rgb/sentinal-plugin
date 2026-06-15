package de.derfakegamer.sentinel.manager;

import de.derfakegamer.sentinel.Sentinel;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import static org.junit.jupiter.api.Assertions.*;

class OrbitalAccessTest {
    ServerMock server; Sentinel plugin;
    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    @Test void ownerIsAlwaysAllowed() {
        plugin.getConfig().set("owner", "Boss");
        PlayerMock boss = server.addPlayer("Boss");
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
