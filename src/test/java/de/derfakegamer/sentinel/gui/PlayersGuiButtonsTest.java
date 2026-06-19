package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import static org.junit.jupiter.api.Assertions.*;

class PlayersGuiButtonsTest {
    ServerMock server; Sentinel plugin;

    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    @Test void vanishButtonTogglesVanish() {
        PlayerMock mod = server.addPlayer("Mod");
        PlayersGui gui = new PlayersGui(plugin, 0);
        gui.open(mod);

        InventoryClickEvent event = ConfirmGuiTest.clickSlot(mod, gui, 49); // Vanish
        gui.onClick(event);

        assertTrue(event.isCancelled());
        assertTrue(plugin.vanish().isVanished(mod.getUniqueId()));
    }

    @Test void reportsButtonOpensReportsGui() {
        PlayerMock mod = server.addPlayer("Mod");
        PlayersGui gui = new PlayersGui(plugin, 0);
        gui.open(mod);

        InventoryClickEvent event = ConfirmGuiTest.clickSlot(mod, gui, 47); // Reports
        gui.onClick(event);

        assertTrue(event.isCancelled());
        assertInstanceOf(ReportsGui.class, mod.getOpenInventory().getTopInventory().getHolder());
    }
}
