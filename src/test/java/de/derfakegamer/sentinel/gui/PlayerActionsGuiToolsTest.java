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
        PlayerActionsGui gui = new PlayerActionsGui(plugin, target, false, false, false, 0, null);
        gui.open(mod);

        InventoryClickEvent event = ConfirmGuiTest.clickSlot(mod, gui, 20); // Freeze
        gui.onClick(event);

        assertTrue(event.isCancelled());
        assertTrue(plugin.freeze().isFrozen(target.getUniqueId()));
    }

    @Test void invseeOpensTargetInventory() {
        PlayerMock mod = server.addPlayer("Mod");
        PlayerMock target = server.addPlayer("Suspect");
        PlayerActionsGui gui = new PlayerActionsGui(plugin, target, false, false, false, 0, null);
        gui.open(mod);

        InventoryClickEvent event = ConfirmGuiTest.clickSlot(mod, gui, 21); // Invsee
        gui.onClick(event);

        assertTrue(event.isCancelled());
        assertInstanceOf(InvseeGui.class, mod.getOpenInventory().getTopInventory().getHolder(),
            "moderator is now viewing the target via the InvseeGui");
    }

    @Test void offlineIpBanUsesStoredIp() throws Exception {
        org.mockbukkit.mockbukkit.entity.PlayerMock mod = server.addPlayer("Mod");
        java.util.UUID offline = java.util.UUID.randomUUID();
        plugin.players().record(offline, "GoneGuy", "7.7.7.7");
        org.bukkit.OfflinePlayer target = server.getOfflinePlayer(offline);

        // Fetch the stored IP as the static open() method would
        de.derfakegamer.sentinel.model.PlayerRecord rec =
            plugin.players().byUuid(offline).get(2, java.util.concurrent.TimeUnit.SECONDS);
        String storedIp = rec != null ? rec.lastIp() : null;

        PlayerActionsGui gui = new PlayerActionsGui(plugin, target, false, false, false, 0, storedIp);
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

        PlayerActionsGui gui = new PlayerActionsGui(plugin, owner, false, false, false, 0, null);
        gui.open(mod);
        org.bukkit.event.inventory.InventoryClickEvent ev = ConfirmGuiTest.clickSlot(mod, gui, 26); // OPTOGGLE
        gui.onClick(ev);

        assertTrue(ev.isCancelled());
        assertTrue(owner.isOp(), "an exempt (owner) player must not be de-opped via the panel");
    }

    @Test void shadowMuteButtonIsShown() {
        org.mockbukkit.mockbukkit.entity.PlayerMock mod = server.addPlayer("Mod");
        org.mockbukkit.mockbukkit.entity.PlayerMock target = server.addPlayer("Sneaky");
        PlayerActionsGui gui = new PlayerActionsGui(plugin, target, false, false, false, 0, null);
        assertNotNull(gui.getInventory().getItem(16));
        assertEquals(org.bukkit.Material.INK_SAC, gui.getInventory().getItem(16).getType());
    }
}
