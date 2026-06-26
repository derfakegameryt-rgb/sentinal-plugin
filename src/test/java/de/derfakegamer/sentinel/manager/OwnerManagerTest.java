package de.derfakegamer.sentinel.manager;

import static org.junit.jupiter.api.Assertions.*;

import de.derfakegamer.sentinel.Sentinel;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class OwnerManagerTest {
    ServerMock server;
    Sentinel plugin;

    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    @Test void isOwnerNameMatchesTheOwnersNameCaseInsensitively() {
        // Make the owner's name resolvable by adding an online player with the owner UUID.
        PlayerMock owner = new PlayerMock(server, "OwnerGuy", plugin.owner().uuid());
        server.addPlayer(owner);

        assertTrue(plugin.owner().isOwnerName("OwnerGuy"), "exact name matches");
        assertTrue(plugin.owner().isOwnerName("ownerguy"), "match is case-insensitive");
        assertFalse(plugin.owner().isOwnerName("SomeoneElse"), "other names do not match");
        assertFalse(plugin.owner().isOwnerName(null), "null name never matches");
    }
}
