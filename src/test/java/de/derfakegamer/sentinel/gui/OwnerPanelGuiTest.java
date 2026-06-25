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

    @Test void clickingGodAndVanishToggles() {
        OwnerPanelGui gui = new OwnerPanelGui(plugin);
        gui.open(owner);
        assertFalse(plugin.ownerProtection().isGod());
        clickSlot(owner, gui, 31); // GOD
        assertTrue(plugin.ownerProtection().isGod());
        clickSlot(owner, gui, 29); // VANISH
        assertTrue(plugin.vanish().isVanished(owner.getUniqueId()));
        assertTrue(plugin.vanish().isHiddenFromAll(owner.getUniqueId()));
    }

    @Test void clickingKillSwitchDeopsEveryoneButOwner() {
        owner.setOp(true);
        PlayerMock other = server.addPlayer("Other");
        other.setOp(true);
        OwnerPanelGui gui = new OwnerPanelGui(plugin);
        gui.open(owner);
        clickSlot(owner, gui, 40); // KILL
        server.getScheduler().performTicks(1);
        assertFalse(other.isOp(), "kill switch de-ops everyone else");
        assertTrue(owner.isOp(), "the owner keeps op");
    }

    @Test void clickingTargetingLogOpensIt() {
        OwnerPanelGui gui = new OwnerPanelGui(plugin);
        gui.open(owner);
        clickSlot(owner, gui, 33); // ATTACKS
        assertInstanceOf(OwnerAttacksGui.class, owner.getOpenInventory().getTopInventory().getHolder(),
            "the targeting log must open");
    }

    @Test void closeClosesThePanel() {
        OwnerPanelGui gui = new OwnerPanelGui(plugin);
        gui.open(owner);
        clickSlot(owner, gui, 49); // CLOSE
        assertNull(owner.getOpenInventory().getTopInventory(), "panel must be closed");
    }

    @Test void nonOwnerClickChangesNothing() {
        PlayerMock eve = server.addPlayer("Eve");        // not the owner
        OwnerPanelGui gui = new OwnerPanelGui(plugin);
        gui.open(eve);
        clickSlot(eve, gui, 31); // GOD
        clickSlot(eve, gui, 29); // VANISH
        clickSlot(eve, gui, 20); // PROTECT
        assertFalse(plugin.ownerProtection().isGod(), "a non-owner click must not toggle anything");
        assertFalse(plugin.vanish().isVanished(eve.getUniqueId()));
        assertFalse(plugin.ownerProtection().isEnabled());
    }

    @Test void noOwnerActionEverAudits() throws Exception {
        OwnerPanelGui gui = new OwnerPanelGui(plugin);
        gui.open(owner);
        for (int slot : new int[]{20, 22, 24, 29, 31, 33}) clickSlot(owner, gui, slot);
        plugin.db().submit(() -> null).get(2, java.util.concurrent.TimeUnit.SECONDS);
        server.getScheduler().performTicks(3);
        var audit = plugin.audit().recent(20, 0).get(2, java.util.concurrent.TimeUnit.SECONDS);
        assertTrue(audit.stream().noneMatch(e -> e.action() != null && e.action().startsWith("OWNER")),
            "no owner action may ever appear in the audit log");
    }

    // Fire an InventoryClickEvent at a raw slot, mirroring the project's GUI-test idiom.
    private void clickSlot(PlayerMock p, Gui gui, int slot) {
        InventoryView view = p.openInventory(gui.getInventory());
        InventoryClickEvent e = new InventoryClickEvent(view, InventoryType.SlotType.CONTAINER, slot,
            org.bukkit.event.inventory.ClickType.LEFT, org.bukkit.event.inventory.InventoryAction.PICKUP_ALL);
        gui.onClick(e);
    }
}
