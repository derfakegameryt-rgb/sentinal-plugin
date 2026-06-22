package de.derfakegamer.sentinel.manager;

import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import de.derfakegamer.sentinel.Sentinel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class OwnerProtectionManagerTest {
    ServerMock server;
    Sentinel plugin;

    @BeforeEach void setUp() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void tearDown() { MockBukkit.unmock(); }

    private void drain() throws Exception {
        plugin.db().submit(() -> null).get(2, TimeUnit.SECONDS);
        server.getScheduler().performTicks(3);
    }

    // ---- pure detector ----
    @Test void affectsOwnerMatchesExactNameCaseInsensitive() {
        assertTrue(OwnerProtectionManager.affectsOwner("/kill DerFakeGamer", "DerFakeGamer"));
        assertTrue(OwnerProtectionManager.affectsOwner("/effect give derfakegamer minecraft:speed", "DerFakeGamer"));
    }
    @Test void affectsOwnerIgnoresSuperstringAndLabel() {
        assertFalse(OwnerProtectionManager.affectsOwner("/give DerFakeGamer123 stone", "DerFakeGamer"));
        assertFalse(OwnerProtectionManager.affectsOwner("/DerFakeGamer", "DerFakeGamer")); // label only
    }
    @Test void affectsOwnerMatchesSelectorsButNotSelf() {
        assertTrue(OwnerProtectionManager.affectsOwner("/kill @a", "DerFakeGamer"));
        assertTrue(OwnerProtectionManager.affectsOwner("/effect @e[type=player] speed", "DerFakeGamer"));
        assertTrue(OwnerProtectionManager.affectsOwner("/kill @p", "Bob"));
        assertFalse(OwnerProtectionManager.affectsOwner("/kill @s", "DerFakeGamer"));
    }
    @Test void affectsOwnerNullSafe() {
        assertFalse(OwnerProtectionManager.affectsOwner(null, "DerFakeGamer"));
        assertFalse(OwnerProtectionManager.affectsOwner("/give Bob stone", null)); // null name, no selector
        assertTrue(OwnerProtectionManager.affectsOwner("/give @a stone", null));   // selector still matches
    }

    private static final String OWNER_UUID = "6500ca9a-a10c-40a5-b985-a56ca9ff1d1e";

    @Test void affectsOwnerMatchesUuidAndExecuteAs() {
        // /execute as <name> is caught by the name token
        assertTrue(OwnerProtectionManager.affectsOwner(
            "/execute as DerFakeGamer run kill @s", "DerFakeGamer", OWNER_UUID));
        // /execute as <uuid> bypass is now closed
        assertTrue(OwnerProtectionManager.affectsOwner(
            "/execute as " + OWNER_UUID + " run kill @s", "DerFakeGamer", OWNER_UUID));
        // UUID match is case-insensitive and works without a resolved name
        assertTrue(OwnerProtectionManager.affectsOwner(
            "/data get entity " + OWNER_UUID.toUpperCase(), null, OWNER_UUID));
        // an unrelated UUID is not matched
        assertFalse(OwnerProtectionManager.affectsOwner(
            "/data get entity 00000000-0000-0000-0000-000000000000", "DerFakeGamer", OWNER_UUID));
    }

    @Test void affectsOwnerMatchesAllSelectorsExceptSelf() {
        assertTrue(OwnerProtectionManager.affectsOwner("/execute as @n run kill @s", "Bob", OWNER_UUID));
        assertTrue(OwnerProtectionManager.affectsOwner("/kill @r", "Bob", OWNER_UUID));
        assertFalse(OwnerProtectionManager.affectsOwner("/execute as @s run say hi", "Bob", OWNER_UUID));
        assertFalse(OwnerProtectionManager.affectsOwner("/say hello world", "Bob", OWNER_UUID));
    }

    // ---- persistence round-trip for all three flags ----
    @Test void flagsPersistAndReload() throws Exception {
        OwnerProtectionManager m = new OwnerProtectionManager(plugin);
        m.setEnabled(true);
        m.setAutoUnban(true);
        m.setAutoWhitelist(true);
        drain();
        OwnerProtectionManager fresh = new OwnerProtectionManager(plugin);
        fresh.load();
        drain();
        assertTrue(fresh.isEnabled());
        assertTrue(fresh.isAutoUnban());
        assertTrue(fresh.isAutoWhitelist());
    }
    @Test void defaultsAreFalse() throws Exception {
        OwnerProtectionManager fresh = new OwnerProtectionManager(plugin);
        fresh.load();
        drain();
        assertFalse(fresh.isEnabled());
        assertFalse(fresh.isAutoUnban());
        assertFalse(fresh.isAutoWhitelist());
    }
}
