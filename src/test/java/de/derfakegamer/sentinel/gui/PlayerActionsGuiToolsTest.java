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

    @Test void exemptPlayerCannotBeDeOpped() {
        org.mockbukkit.mockbukkit.entity.PlayerMock mod = server.addPlayer("Mod");
        org.mockbukkit.mockbukkit.entity.PlayerMock owner = server.addPlayer("Owner");
        owner.setOp(true);
        plugin.getConfig().set("exempt", java.util.List.of(owner.getUniqueId().toString()));
        plugin.saveConfig();
        plugin.reloadAll(); // rebuilds the punishment manager with the exempt set

        PlayerActionsGui gui = new PlayerActionsGui(plugin, owner);
        gui.open(mod);
        org.bukkit.event.inventory.InventoryClickEvent ev = ConfirmGuiTest.clickSlot(mod, gui, 26); // OPTOGGLE
        gui.onClick(ev);

        assertTrue(ev.isCancelled());
        assertTrue(owner.isOp(), "an exempt (owner) player must not be de-opped via the panel");
    }
}
