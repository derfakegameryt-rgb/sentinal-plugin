package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.Appeal;
import de.derfakegamer.sentinel.model.PunishmentType;
import org.bukkit.Material;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class AppealsGuiTest {
    ServerMock server;
    Sentinel plugin;

    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    @Test void rendersOneItemPerAppeal() throws Exception {
        long now = System.currentTimeMillis();
        plugin.appeals().submit(UUID.randomUUID(), "Alice", 1, PunishmentType.MUTE, "sorry", now).get(2, TimeUnit.SECONDS);
        plugin.appeals().submit(UUID.randomUUID(), "Bob", 2, PunishmentType.BAN, "please unban", now).get(2, TimeUnit.SECONDS);

        List<Appeal> appeals = plugin.appeals().open().get(2, TimeUnit.SECONDS);
        AppealsGui gui = new AppealsGui(plugin, 0, appeals);

        int heads = 0;
        for (int i = 0; i < 45; i++) {
            var it = gui.getInventory().getItem(i);
            if (it != null && it.getType() == Material.PLAYER_HEAD) heads++;
        }
        assertEquals(2, heads);
    }

    @Test void denyClickFiresAndRefreshes() throws Exception {
        long now = System.currentTimeMillis();
        plugin.appeals().submit(UUID.randomUUID(), "Charlie", 3, PunishmentType.MUTE, "pls", now).get(2, TimeUnit.SECONDS);

        List<Appeal> appeals = plugin.appeals().open().get(2, TimeUnit.SECONDS);
        assertEquals(1, appeals.size());

        PlayerMock mod = server.addPlayer("Mod");
        mod.addAttachment(plugin, "sentinel.use", true);

        AppealsGui gui = new AppealsGui(plugin, 0, appeals);

        // Right-click slot 0 → deny
        InventoryClickEvent ev = new InventoryClickEvent(
            mod.openInventory(gui.getInventory()),
            InventoryType.SlotType.CONTAINER, 0,
            ClickType.RIGHT, InventoryAction.PICKUP_ALL);
        gui.onClick(ev);
        assertTrue(ev.isCancelled());

        // drain the DB executor so the deny write completes
        plugin.appeals().open().get(2, TimeUnit.SECONDS);

        // appeal should now be denied (not in open list)
        List<Appeal> remaining = plugin.appeals().open().get(2, TimeUnit.SECONDS);
        assertTrue(remaining.isEmpty(), "denied appeal must no longer appear in open list");
    }
}
