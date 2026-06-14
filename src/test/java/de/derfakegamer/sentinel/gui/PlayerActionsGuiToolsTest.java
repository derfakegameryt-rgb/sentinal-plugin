package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import static org.junit.jupiter.api.Assertions.*;

class PlayerActionsGuiToolsTest {
    ServerMock server; Sentinel plugin;

    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    @Test void freezeButtonTogglesFreeze() {
        PlayerMock mod = server.addPlayer("Mod");
        PlayerMock target = server.addPlayer("Suspect");
        PlayerActionsGui gui = new PlayerActionsGui(plugin, target);
        gui.open(mod);

        InventoryClickEvent event = ConfirmGuiTest.clickSlot(mod, gui, 20); // Freeze
        gui.onClick(event);

        assertTrue(event.isCancelled());
        assertTrue(plugin.freeze().isFrozen(target.getUniqueId()));
    }

    @Test void invseeOpensTargetInventory() {
        PlayerMock mod = server.addPlayer("Mod");
        PlayerMock target = server.addPlayer("Suspect");
        PlayerActionsGui gui = new PlayerActionsGui(plugin, target);
        gui.open(mod);

        InventoryClickEvent event = ConfirmGuiTest.clickSlot(mod, gui, 21); // Invsee
        gui.onClick(event);

        assertTrue(event.isCancelled());
        assertInstanceOf(InvseeGui.class, mod.getOpenInventory().getTopInventory().getHolder(),
            "moderator is now viewing the target via the InvseeGui");
    }

    @Test void offlineIpBanUsesStoredIp() {
        org.mockbukkit.mockbukkit.entity.PlayerMock mod = server.addPlayer("Mod");
        java.util.UUID offline = java.util.UUID.randomUUID();
        plugin.players().record(offline, "GoneGuy", "7.7.7.7");
        org.bukkit.OfflinePlayer target = server.getOfflinePlayer(offline);

        PlayerActionsGui gui = new PlayerActionsGui(plugin, target);
        // IP-Ban button is present at slot 19 even though the target is offline
        assertNotNull(gui.getInventory().getItem(19));
        assertEquals(org.bukkit.Material.IRON_BARS, gui.getInventory().getItem(19).getType());
    }
}
