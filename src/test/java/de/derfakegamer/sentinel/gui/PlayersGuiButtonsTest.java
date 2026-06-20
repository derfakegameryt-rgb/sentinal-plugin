package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PlayersGuiButtonsTest {
    ServerMock server; Sentinel plugin;

    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    private PlayersGui emptyGui() {
        return new PlayersGui(plugin, 0, new ArrayList<>(), new boolean[0], new int[0], 0);
    }

    @Test void playerListNoLongerHasVanishOrStaffChat() throws Exception {
        PlayerMock mod = server.addPlayer("Mod2");
        PlayersGui.open(plugin, 0, mod);
        plugin.db().submit(() -> null).get(2, java.util.concurrent.TimeUnit.SECONDS);
        server.getScheduler().performTicks(2);
        var inv = mod.getOpenInventory().getTopInventory();
        // slots 48 (staff) and 49 (vanish) must now be empty/border-filled, not the toggle buttons
        var staff = inv.getItem(48);
        var vanish = inv.getItem(49);
        assertTrue(staff == null || staff.getType() != org.bukkit.Material.NETHER_STAR,
            "slot 48 should not be staff chat (NETHER_STAR)");
        assertTrue(vanish == null || vanish.getType() != org.bukkit.Material.ENDER_EYE,
            "slot 49 should not be vanish (ENDER_EYE)");
    }

    @Test void reportsButtonOpensReportsGui() throws Exception {
        PlayerMock mod = server.addPlayer("Mod");
        PlayersGui gui = emptyGui();
        gui.open(mod);

        InventoryClickEvent event = ConfirmGuiTest.clickSlot(mod, gui, 47); // Reports
        gui.onClick(event);

        // ReportsGui.open is async; drain DB executor then tick Bukkit scheduler
        plugin.db().submit(() -> null).get(2, java.util.concurrent.TimeUnit.SECONDS);
        server.getScheduler().performTicks(1);

        assertTrue(event.isCancelled());
        assertInstanceOf(ReportsGui.class, mod.getOpenInventory().getTopInventory().getHolder());
    }
}
