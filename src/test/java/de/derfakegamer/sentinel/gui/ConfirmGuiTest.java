package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import net.kyori.adventure.text.Component;
import org.bukkit.event.inventory.*;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class ConfirmGuiTest {
    ServerMock server; Sentinel plugin;

    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    @Test void confirmRunsActionAndIsCancelled() {
        PlayerMock p = server.addPlayer("Mod");
        AtomicBoolean ran = new AtomicBoolean(false);
        ConfirmGui gui = new ConfirmGui(plugin, Component.text("Ban Griefer?"), () -> ran.set(true), null);
        gui.open(p);

        InventoryClickEvent event = clickSlot(p, gui, 11); // confirm
        gui.onClick(event);

        assertTrue(event.isCancelled(), "GUI clicks must be cancelled");
        assertTrue(ran.get(), "confirm must run the action");
    }

    @Test void cancelDoesNotRunAction() {
        PlayerMock p = server.addPlayer("Mod");
        AtomicBoolean ran = new AtomicBoolean(false);
        ConfirmGui gui = new ConfirmGui(plugin, Component.text("Ban Griefer?"), () -> ran.set(true), null);
        gui.open(p);

        InventoryClickEvent event = clickSlot(p, gui, 15); // cancel
        gui.onClick(event);

        assertTrue(event.isCancelled());
        assertFalse(ran.get());
    }

    /**
     * Shared helper used by Tasks 4-7 as {@code ConfirmGuiTest.clickSlot(...)}.
     * Opens the GUI's inventory for the player and builds a left-click PICKUP_ALL
     * event on {@code slot} within the resulting view's top (container) inventory.
     */
    static InventoryClickEvent clickSlot(PlayerMock p, Gui gui, int slot) {
        return new InventoryClickEvent(p.openInventory(gui.getInventory()),
            InventoryType.SlotType.CONTAINER, slot, org.bukkit.event.inventory.ClickType.LEFT,
            InventoryAction.PICKUP_ALL);
    }
}
