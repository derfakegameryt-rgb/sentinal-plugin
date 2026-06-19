package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.PunishmentType;
import org.bukkit.OfflinePlayer;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class PlayerActionsGuiTest {
    ServerMock server; Sentinel plugin;

    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    @Test void banButtonOpensReasonGui() {
        PlayerMock mod = server.addPlayer("Mod"); mod.setOp(true);
        OfflinePlayer target = server.addPlayer("Griefer");
        // target is not banned — construct with banned=false
        PlayerActionsGui gui = new PlayerActionsGui(plugin, target, false, false, false, 0, null);
        gui.open(mod);

        InventoryClickEvent event = ConfirmGuiTest.clickSlot(mod, gui, 10); // Ban
        gui.onClick(event);

        assertTrue(event.isCancelled());
        assertInstanceOf(ReasonGui.class, mod.getOpenInventory().getTopInventory().getHolder());
    }

    @Test void unbanButtonShownWhenBannedAndRemovesBan() throws Exception {
        PlayerMock mod = server.addPlayer("Mod"); mod.setOp(true);
        OfflinePlayer target = server.addPlayer("Griefer");
        plugin.punishments().ban(target.getUniqueId(), "Griefer", mod.getUniqueId(), "Mod", "x", 0).get(2, TimeUnit.SECONDS);
        // construct with banned=true (pre-fetched)
        PlayerActionsGui gui = new PlayerActionsGui(plugin, target, true, false, false, 0, null);
        gui.open(mod);

        InventoryClickEvent event = ConfirmGuiTest.clickSlot(mod, gui, 10); // now "Unban"
        gui.onClick(event);

        assertTrue(event.isCancelled());
        // Wait for the async removeBan future to complete on the DB thread
        Thread.sleep(200);
        server.getScheduler().performTicks(2);
        assertNull(plugin.punishments().activeBan(target.getUniqueId(), System.currentTimeMillis()).get(2, TimeUnit.SECONDS),
            "clicking Unban removes the active ban");
    }
}
