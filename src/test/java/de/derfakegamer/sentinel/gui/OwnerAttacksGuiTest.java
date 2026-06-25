package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.InventoryView;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class OwnerAttacksGuiTest {
    ServerMock server; Sentinel plugin; PlayerMock owner;

    @BeforeEach void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(Sentinel.class);
        owner = new PlayerMock(server, "DerFakeGamer", plugin.owner().uuid());
        server.addPlayer(owner);
    }
    @AfterEach void tearDown() { MockBukkit.unmock(); }

    @Test void emptyLogOpensWithPlaceholder() {
        OwnerAttacksGui gui = new OwnerAttacksGui(plugin);
        gui.open(owner);
        assertEquals(54, gui.getInventory().getSize());
        assertNotNull(gui.getInventory().getItem(22), "empty log shows a placeholder");
    }

    @Test void recordedAttemptsRenderAsHeads() {
        plugin.ownerProtection().recordAttempt("Mallory", UUID.randomUUID(), "/ban DerFakeGamer");
        plugin.ownerProtection().recordAttempt("Eve", UUID.randomUUID(), "opened player menu");
        OwnerAttacksGui gui = new OwnerAttacksGui(plugin);
        gui.open(owner);
        assertNotNull(gui.getInventory().getItem(0), "newest attempt at slot 0");
        assertNotNull(gui.getInventory().getItem(1));
    }

    @Test void consoleAttemptRendersWithoutPlayerHead() {
        plugin.ownerProtection().recordAttempt("Command Block", null, "kill DerFakeGamer");
        OwnerAttacksGui gui = new OwnerAttacksGui(plugin);
        assertDoesNotThrow(() -> gui.open(owner));   // null uuid must not NPE the head lookup
        assertNotNull(gui.getInventory().getItem(0));
    }

    @Test void manyAttemptsRenderWithinCapWithoutError() {
        for (int i = 0; i < 60; i++) plugin.ownerProtection().recordAttempt("P" + i, UUID.randomUUID(), "/cmd" + i);
        OwnerAttacksGui gui = new OwnerAttacksGui(plugin);   // ring buffer caps at 30, GUI caps render at 45
        assertDoesNotThrow(() -> gui.open(owner));
    }

    @Test void backReturnsToPanel() {
        OwnerAttacksGui gui = new OwnerAttacksGui(plugin);
        gui.open(owner);
        click(owner, gui, 48); // BACK
        assertInstanceOf(OwnerPanelGui.class, owner.getOpenInventory().getTopInventory().getHolder());
    }

    @Test void nonOwnerCannotInteract() {
        PlayerMock eve = server.addPlayer("Eve");
        OwnerAttacksGui gui = new OwnerAttacksGui(plugin);
        gui.open(eve);
        assertDoesNotThrow(() -> click(eve, gui, 48)); // BACK as non-owner -> just closes, no panel
        assertNull(eve.getOpenInventory().getTopInventory(),
            "a non-owner must be bounced out, not into the owner panel");
    }

    private void click(PlayerMock p, Gui gui, int slot) {
        InventoryView view = p.openInventory(gui.getInventory());
        InventoryClickEvent e = new InventoryClickEvent(view, InventoryType.SlotType.CONTAINER, slot,
            ClickType.LEFT, InventoryAction.PICKUP_ALL);
        gui.onClick(e);
    }
}
