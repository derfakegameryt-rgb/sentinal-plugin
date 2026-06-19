package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import org.bukkit.Material;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import static org.junit.jupiter.api.Assertions.*;

class PlayersGuiTest {
    ServerMock server; Sentinel plugin;

    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    @Test void showsOnlinePlayersAsHeads() {
        server.addPlayer("Alice");
        server.addPlayer("Bob");
        PlayersGui gui = new PlayersGui(plugin, 0);
        int heads = 0;
        for (int i = 0; i <= 44; i++) {
            var item = gui.getInventory().getItem(i);
            if (item != null && item.getType() == Material.PLAYER_HEAD) heads++;
        }
        assertEquals(2, heads);
    }

    @Test void clickingHeadOpensActions() {
        PlayerMock mod = server.addPlayer("Mod");
        PlayersGui gui = new PlayersGui(plugin, 0);
        gui.open(mod);
        // find the slot holding a head (Mod is the only online player)
        int slot = -1;
        for (int i = 0; i <= 44; i++)
            if (gui.getInventory().getItem(i) != null && gui.getInventory().getItem(i).getType() == Material.PLAYER_HEAD) { slot = i; break; }
        assertTrue(slot >= 0);

        InventoryClickEvent event = ConfirmGuiTest.clickSlot(mod, gui, slot);
        gui.onClick(event);

        assertTrue(event.isCancelled());
        assertInstanceOf(PlayerActionsGui.class, mod.getOpenInventory().getTopInventory().getHolder());
    }
}
