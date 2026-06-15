package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import org.bukkit.Material;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ActiveBansGuiTest {
    ServerMock server; Sentinel plugin;

    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    @Test void listsActiveBans() {
        plugin.punishments().ban(UUID.randomUUID(), "Banned1", new UUID(0,0), "Admin", "x", 0);
        plugin.punishments().ban(UUID.randomUUID(), "Banned2", new UUID(0,0), "Admin", "x", 0);
        ActiveBansGui gui = new ActiveBansGui(plugin, 0);
        int items = 0;
        for (int i = 0; i <= 44; i++) {
            var it = gui.getInventory().getItem(i);
            if (it != null && it.getType() == Material.PLAYER_HEAD) items++;
        }
        assertEquals(2, items);
    }
}
