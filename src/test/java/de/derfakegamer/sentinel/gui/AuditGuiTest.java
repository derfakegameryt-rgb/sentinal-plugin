package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class AuditGuiTest {
    ServerMock server; Sentinel plugin;
    @BeforeEach void setUp() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void tearDown() { MockBukkit.unmock(); }

    @Test void rendersAuditEntries() throws Exception {
        plugin.audit().record("Mod", "BAN", "Bob", "spam");
        plugin.db().submit(() -> null).get(2, TimeUnit.SECONDS);   // drain insert
        Player p = server.addPlayer();
        AuditGui.open(plugin, p, 0);
        plugin.db().submit(() -> null).get(2, TimeUnit.SECONDS);   // drain the recent() read
        server.getScheduler().performTicks(2);                     // flush the callback
        assertInstanceOf(AuditGui.class, p.getOpenInventory().getTopInventory().getHolder());
        // at least one non-border item rendered (the entry)
        assertTrue(p.getOpenInventory().getTopInventory().getItem(0) != null);
    }
}
