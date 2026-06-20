package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class ModStatsGuiTest {
    ServerMock server; Sentinel plugin;
    @BeforeEach void setUp() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void tearDown() { MockBukkit.unmock(); }

    @Test void opensWithStats() throws Exception {
        plugin.audit().record("Mod", "BAN", "Bob", "");
        plugin.db().submit(() -> null).get(2, TimeUnit.SECONDS);
        Player p = server.addPlayer();
        ModStatsGui.open(plugin, p);
        plugin.db().submit(() -> null).get(2, TimeUnit.SECONDS);
        server.getScheduler().performTicks(2);
        assertInstanceOf(ModStatsGui.class, p.getOpenInventory().getTopInventory().getHolder());
    }
}
