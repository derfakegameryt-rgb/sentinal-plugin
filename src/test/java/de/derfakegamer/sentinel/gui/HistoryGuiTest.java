package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import org.bukkit.OfflinePlayer;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class HistoryGuiTest {
    ServerMock server; Sentinel plugin;

    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    @Test void rendersOneItemPerPunishment() {
        PlayerMock mod = server.addPlayer("Mod");
        OfflinePlayer target = server.addPlayer("Griefer");
        UUID t = target.getUniqueId();
        plugin.punishments().warn(t, "Griefer", mod.getUniqueId(), "Mod", "spam");
        plugin.punishments().warn(t, "Griefer", mod.getUniqueId(), "Mod", "spam again");

        HistoryGui gui = new HistoryGui(plugin, target, 0);
        int items = 0;
        for (int i = 0; i <= 44; i++)
            if (gui.getInventory().getItem(i) != null && gui.getInventory().getItem(i).getType() != org.bukkit.Material.LIGHT_BLUE_STAINED_GLASS_PANE)
                items++;
        assertEquals(2, items);
    }

    @Test void emptyHistoryHasNoEntryItems() {
        PlayerMock mod = server.addPlayer("Mod");
        OfflinePlayer target = server.addPlayer("Clean");
        HistoryGui gui = new HistoryGui(plugin, target, 0);
        for (int i = 0; i <= 44; i++) {
            var item = gui.getInventory().getItem(i);
            assertTrue(item == null || item.getType() == org.bukkit.Material.LIGHT_BLUE_STAINED_GLASS_PANE,
                "no real entries for a clean player");
        }
    }
}
