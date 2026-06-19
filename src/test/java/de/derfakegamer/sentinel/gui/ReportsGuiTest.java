package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.Report;
import org.bukkit.Material;
import org.bukkit.event.inventory.*;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ReportsGuiTest {
    ServerMock server; Sentinel plugin;

    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    @Test void rendersOneItemPerOpenReport() throws Exception {
        PlayerMock reporter = server.addPlayer("Reporter");
        PlayerMock target = server.addPlayer("Target");
        plugin.reports().file(reporter, target.getUniqueId(), target.getName(), "hacking")
                .get(2, TimeUnit.SECONDS);

        List<Report> reports = plugin.reports().open().get(2, TimeUnit.SECONDS);
        ReportsGui gui = new ReportsGui(plugin, 0, reports);
        int items = 0;
        for (int i = 0; i <= 44; i++) {
            var item = gui.getInventory().getItem(i);
            if (item != null && item.getType() != Material.GRAY_STAINED_GLASS_PANE) items++;
        }
        assertEquals(1, items);
    }

    @Test void shiftClickMarksHandled() throws Exception {
        PlayerMock mod = server.addPlayer("Mod"); mod.setOp(true);
        PlayerMock reporter = server.addPlayer("Reporter");
        PlayerMock target = server.addPlayer("Target");
        plugin.reports().file(reporter, target.getUniqueId(), target.getName(), "hacking")
                .get(2, TimeUnit.SECONDS);

        List<Report> reports = plugin.reports().open().get(2, TimeUnit.SECONDS);
        ReportsGui gui = new ReportsGui(plugin, 0, reports);
        gui.open(mod);
        InventoryClickEvent event = new InventoryClickEvent(mod.openInventory(gui.getInventory()),
            InventoryType.SlotType.CONTAINER, 0, ClickType.SHIFT_LEFT, InventoryAction.PICKUP_ALL);
        gui.onClick(event);

        assertTrue(event.isCancelled());
        // handle is fire-and-forget; drain DB executor before asserting
        plugin.db().submit(() -> null).get(2, TimeUnit.SECONDS);
        assertEquals(0, plugin.reports().open().get(2, TimeUnit.SECONDS).size(),
                "shift-click handles the report");
    }
}
