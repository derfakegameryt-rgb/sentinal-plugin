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
