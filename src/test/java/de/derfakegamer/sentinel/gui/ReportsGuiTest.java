package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import org.bukkit.Material;
import org.bukkit.event.inventory.*;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import static org.junit.jupiter.api.Assertions.*;

class ReportsGuiTest {
    ServerMock server; Sentinel plugin;

    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    @Test void rendersOneItemPerOpenReport() {
        PlayerMock reporter = server.addPlayer("Reporter");
        PlayerMock target = server.addPlayer("Target");
        plugin.reports().file(reporter, target.getUniqueId(), target.getName(), "hacking");

        ReportsGui gui = new ReportsGui(plugin, 0);
        int items = 0;
        for (int i = 0; i <= 44; i++) {
            var item = gui.getInventory().getItem(i);
            if (item != null && item.getType() != Material.LIGHT_BLUE_STAINED_GLASS_PANE) items++;
        }
        assertEquals(1, items);
    }

    @Test void shiftClickMarksHandled() {
        PlayerMock mod = server.addPlayer("Mod"); mod.setOp(true);
        PlayerMock reporter = server.addPlayer("Reporter");
        PlayerMock target = server.addPlayer("Target");
        plugin.reports().file(reporter, target.getUniqueId(), target.getName(), "hacking");

        ReportsGui gui = new ReportsGui(plugin, 0);
        gui.open(mod);
        InventoryClickEvent event = new InventoryClickEvent(mod.openInventory(gui.getInventory()),
            InventoryType.SlotType.CONTAINER, 0, ClickType.SHIFT_LEFT, InventoryAction.PICKUP_ALL);
        gui.onClick(event);

        assertTrue(event.isCancelled());
        assertEquals(0, plugin.reports().open().size(), "shift-click handles the report");
    }
}
