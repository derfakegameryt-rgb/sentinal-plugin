package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import org.bukkit.Material;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import static org.junit.jupiter.api.Assertions.*;

class GuiLayoutTest {
    ServerMock server; Sentinel plugin;
    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    @Test void menuGuiHasGrayBorder() {
        AdminPanelGui gui = new AdminPanelGui(plugin);
        // corners of a 27-slot menu are gray-glass border
        for (int slot : new int[]{0, 8, 18, 26})
            assertEquals(Material.GRAY_STAINED_GLASS_PANE, gui.getInventory().getItem(slot).getType());
    }

    @Test void playersListIsSorted() {
        server.addPlayer("Zebra");
        server.addPlayer("alpha");
        PlayersGui gui = new PlayersGui(plugin, 0);
        // first head should be "alpha" (case-insensitive sort)
        var first = gui.getInventory().getItem(0);
        assertNotNull(first);
        assertEquals(Material.PLAYER_HEAD, first.getType());
        assertTrue(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
            .serialize(first.getItemMeta().displayName()).equalsIgnoreCase("alpha"));
    }
}
