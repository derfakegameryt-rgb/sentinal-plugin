package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import static org.junit.jupiter.api.Assertions.*;

class AdminPanelGuiTest {
    ServerMock server; Sentinel plugin;

    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    @Test void panelHasTheFiveSections() {
        AdminPanelGui gui = new AdminPanelGui(plugin);
        for (int slot : new int[]{10, 11, 12, 13, 14})
            assertNotNull(gui.getInventory().getItem(slot), "section button at " + slot);
    }

    @Test void serverInfoOpensFromPanel() {
        PlayerMock op = server.addPlayer("Admin"); op.setOp(true);
        AdminPanelGui gui = new AdminPanelGui(plugin);
        gui.open(op);
        InventoryClickEvent ev = ConfirmGuiTest.clickSlot(op, gui, 10); // Server Info
        gui.onClick(ev);
        assertTrue(ev.isCancelled());
        assertInstanceOf(ServerInfoGui.class, op.getOpenInventory().getTopInventory().getHolder());
    }

    @Test void hubHasPlayerManagerVanishStaffChat() {
        PlayerMock p = server.addPlayer();
        AdminPanelGui gui = new AdminPanelGui(plugin);
        gui.open(p);
        var inv = p.getOpenInventory().getTopInventory();
        // Player Manager at slot 19, Vanish at 20, Staff Chat at 21
        assertNotNull(inv.getItem(19), "Player Manager at slot 19");
        assertNotNull(inv.getItem(20), "Vanish at slot 20");
        assertNotNull(inv.getItem(21), "Staff Chat at slot 21");
    }

    @Test void clickingPlayerManagerOpensPlayersList() throws Exception {
        PlayerMock p = server.addPlayer();
        AdminPanelGui gui = new AdminPanelGui(plugin);
        gui.open(p);
        InventoryClickEvent ev = ConfirmGuiTest.clickSlot(p, gui, 19);
        gui.onClick(ev);
        // PlayersGui.open is async (DB-backed) -> drain + tick
        plugin.db().submit(() -> null).get(2, java.util.concurrent.TimeUnit.SECONDS);
        server.getScheduler().performTicks(2);
        assertTrue(ev.isCancelled());
        assertInstanceOf(PlayersGui.class, p.getOpenInventory().getTopInventory().getHolder());
    }

    @Test void clickingVanishTogglesVanish() {
        PlayerMock p = server.addPlayer();
        AdminPanelGui gui = new AdminPanelGui(plugin);
        gui.open(p);
        boolean before = plugin.vanish().isVanished(p.getUniqueId());
        InventoryClickEvent ev = ConfirmGuiTest.clickSlot(p, gui, 20);
        gui.onClick(ev);
        assertTrue(ev.isCancelled());
        assertNotEquals(before, plugin.vanish().isVanished(p.getUniqueId()));
    }
}
