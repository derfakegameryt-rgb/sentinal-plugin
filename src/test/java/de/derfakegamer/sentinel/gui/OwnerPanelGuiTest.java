package de.derfakegamer.sentinel.gui;

import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import de.derfakegamer.sentinel.Sentinel;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.InventoryView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OwnerPanelGuiTest {
    ServerMock server;
    Sentinel plugin;
    PlayerMock owner;

    @BeforeEach void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(Sentinel.class);
        owner = new PlayerMock(server, "DerFakeGamer", plugin.owner().uuid());
        server.addPlayer(owner);
    }
    @AfterEach void tearDown() { MockBukkit.unmock(); }

    @Test void panelOpensWith54Slots() {
        OwnerPanelGui gui = new OwnerPanelGui(plugin);
        gui.open(owner);
        assertEquals(54, gui.getInventory().getSize());
    }

    @Test void clickingProtectToggleFlipsAndLeavesNoAuditTrace() throws Exception {
        OwnerPanelGui gui = new OwnerPanelGui(plugin);
        gui.open(owner);
        assertFalse(plugin.ownerProtection().isEnabled());
        clickSlot(owner, gui, 20); // PROTECT
        assertTrue(plugin.ownerProtection().isEnabled());
        plugin.db().submit(() -> null).get(2, java.util.concurrent.TimeUnit.SECONDS);
        server.getScheduler().performTicks(3);
        // The owner feature must be invisible: no OWNER_* entry may surface in the audit views.
        var audit = plugin.audit().recent(10, 0).get(2, java.util.concurrent.TimeUnit.SECONDS);
        assertTrue(audit.stream().noneMatch(e -> e.action() != null && e.action().startsWith("OWNER")),
            "owner toggles must never appear in the audit log");
    }

    @Test void clickingAutoUnbanAndWhitelistToggles() {
        OwnerPanelGui gui = new OwnerPanelGui(plugin);
        gui.open(owner);
        clickSlot(owner, gui, 22); // AUTO_UNBAN
        clickSlot(owner, gui, 24); // AUTO_WHITELIST
        assertTrue(plugin.ownerProtection().isAutoUnban());
        assertTrue(plugin.ownerProtection().isAutoWhitelist());
    }

    // Fire an InventoryClickEvent at a raw slot, mirroring the project's GUI-test idiom.
    private void clickSlot(PlayerMock p, Gui gui, int slot) {
        InventoryView view = p.openInventory(gui.getInventory());
        InventoryClickEvent e = new InventoryClickEvent(view, InventoryType.SlotType.CONTAINER, slot,
            org.bukkit.event.inventory.ClickType.LEFT, org.bukkit.event.inventory.InventoryAction.PICKUP_ALL);
        gui.onClick(e);
    }
}
