package de.derfakegamer.sentinel.command;

import de.derfakegamer.sentinel.Sentinel;
import org.bukkit.command.Command;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import static org.junit.jupiter.api.Assertions.*;

class OwnerAccessTest {
    ServerMock server; Sentinel plugin;
    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    @Test void onlyOwnerSeesOwnerInTab() {
        plugin.getConfig().set("owner", "Boss");
        PlayerMock boss = server.addPlayer("Boss"); boss.setOp(true);
        PlayerMock other = server.addPlayer("Admin"); other.setOp(true);
        SentinelCommand cmd = new SentinelCommand(plugin);
        Command sentinel = server.getCommandMap().getCommand("sentinel");
        assertTrue(cmd.onTabComplete(boss, sentinel, "sentinel", new String[]{"ow"}).contains("owner"));
        assertFalse(cmd.onTabComplete(other, sentinel, "sentinel", new String[]{"ow"}).contains("owner"));
    }

    @Test void nonOwnerOwnerSubcommandDoesNotOpenPanel() {
        plugin.getConfig().set("owner", "Boss");
        PlayerMock other = server.addPlayer("Admin"); other.setOp(true);
        SentinelCommand cmd = new SentinelCommand(plugin);
        cmd.onCommand(other, server.getCommandMap().getCommand("sentinel"), "sentinel", new String[]{"owner"});
        org.bukkit.inventory.InventoryView view = other.getOpenInventory();
        assertFalse(view != null && view.getTopInventory() != null
            && view.getTopInventory().getHolder() instanceof de.derfakegamer.sentinel.gui.OwnerPanelGui);
    }
}
