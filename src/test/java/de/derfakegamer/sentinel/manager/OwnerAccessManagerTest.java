package de.derfakegamer.sentinel.manager;

import de.derfakegamer.sentinel.Sentinel;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import static org.junit.jupiter.api.Assertions.*;

class OwnerAccessManagerTest {
    static final java.util.UUID OWNER = java.util.UUID.fromString("6500ca9a-a10c-40a5-b985-a56ca9ff1d1e");
    ServerMock server; Sentinel plugin;
    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    @Test void ownerHasAllPermsWithoutOp() {
        PlayerMock owner = new PlayerMock(server, "Owner", OWNER);
        server.addPlayer(owner);
        plugin.ownerAccess().grant(owner);
        assertTrue(owner.hasPermission("sentinel.use"));
        assertTrue(owner.hasPermission("sentinel.ban"));
        assertFalse(owner.isOp(), "owner must not be OP");
    }

    @Test void nonOwnerGetsNothing() {
        PlayerMock other = server.addPlayer("Admin"); // not op
        plugin.ownerAccess().grant(other);
        assertFalse(other.hasPermission("sentinel.use"));
    }

    @Test void revokeRemovesAccess() {
        PlayerMock owner = new PlayerMock(server, "Owner", OWNER);
        server.addPlayer(owner);
        plugin.ownerAccess().grant(owner);
        plugin.ownerAccess().revoke(owner);
        assertFalse(owner.hasPermission("sentinel.use"));
    }
}
