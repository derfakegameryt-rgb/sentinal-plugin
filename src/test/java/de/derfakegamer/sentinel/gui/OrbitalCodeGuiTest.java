package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import static org.junit.jupiter.api.Assertions.*;

class OrbitalCodeGuiTest {
    ServerMock server; Sentinel plugin;

    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    private void type(OrbitalCodeGui gui, PlayerMock p, char digit) {
        gui.onClick(click(p, gui, gui.slotForDigit(digit)));
    }
    private InventoryClickEvent click(PlayerMock p, Gui gui, int slot) {
        return ConfirmGuiTest.clickSlot(p, gui, slot);
    }

    @Test void correctCodeOpensModeMenu() {
        PlayerMock p = server.addPlayer("Admin"); p.setOp(true);
        OrbitalCodeGui gui = new OrbitalCodeGui(plugin);
        gui.open(p);
        for (char c : "2584".toCharArray()) type(gui, p, c);
        assertInstanceOf(OrbitalModeGui.class, p.getOpenInventory().getTopInventory().getHolder());
    }

    @Test void wrongCodeDoesNotOpenModeMenu() {
        PlayerMock p = server.addPlayer("Admin"); p.setOp(true);
        OrbitalCodeGui gui = new OrbitalCodeGui(plugin);
        gui.open(p);
        for (char c : "1111".toCharArray()) type(gui, p, c);
        assertFalse(p.getOpenInventory().getTopInventory().getHolder() instanceof OrbitalModeGui);
    }
}
