package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.PunishmentType;
import org.bukkit.OfflinePlayer;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import static org.junit.jupiter.api.Assertions.*;

class PlayerActionsGuiTest {
    ServerMock server; Sentinel plugin;

    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    @Test void banButtonOpensReasonGui() {
        PlayerMock mod = server.addPlayer("Mod");
        OfflinePlayer target = server.addPlayer("Griefer");
        PlayerActionsGui gui = new PlayerActionsGui(plugin, target);
        gui.open(mod);

        InventoryClickEvent event = ConfirmGuiTest.clickSlot(mod, gui, 10); // Ban
        gui.onClick(event);

        assertTrue(event.isCancelled());
        assertInstanceOf(ReasonGui.class, mod.getOpenInventory().getTopInventory().getHolder());
    }

    @Test void unbanButtonShownWhenBannedAndRemovesBan() {
        PlayerMock mod = server.addPlayer("Mod");
        OfflinePlayer target = server.addPlayer("Griefer");
        plugin.punishments().ban(target.getUniqueId(), "Griefer", mod.getUniqueId(), "Mod", "x", 0);
        PlayerActionsGui gui = new PlayerActionsGui(plugin, target);
        gui.open(mod);

        InventoryClickEvent event = ConfirmGuiTest.clickSlot(mod, gui, 10); // now "Unban"
        gui.onClick(event);

        assertTrue(event.isCancelled());
        assertNull(plugin.punishments().activeBan(target.getUniqueId(), System.currentTimeMillis()),
            "clicking Unban removes the active ban");
    }
}
