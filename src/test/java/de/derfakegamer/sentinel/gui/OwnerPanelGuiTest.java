package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.bukkit.event.inventory.InventoryClickEvent;
import static org.junit.jupiter.api.Assertions.*;

class OwnerPanelGuiTest {
    ServerMock server; Sentinel plugin;
    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    @Test void usersButtonOpensUsersGui() {
        PlayerMock p = server.addPlayer("Boss");
        OwnerPanelGui gui = new OwnerPanelGui(plugin);
        gui.open(p);
        InventoryClickEvent ev = ConfirmGuiTest.clickSlot(p, gui, 11);
        gui.onClick(ev);
        assertInstanceOf(OrbitalUsersGui.class, p.getOpenInventory().getTopInventory().getHolder());
    }
}
