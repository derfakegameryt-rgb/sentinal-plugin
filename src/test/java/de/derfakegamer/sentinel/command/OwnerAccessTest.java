package de.derfakegamer.sentinel.command;

import de.derfakegamer.sentinel.Sentinel;
import org.bukkit.command.Command;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import static org.junit.jupiter.api.Assertions.*;

class OwnerAccessTest {
    static final java.util.UUID OWNER = java.util.UUID.fromString("6500ca9a-a10c-40a5-b985-a56ca9ff1d1e");
    ServerMock server; Sentinel plugin;
    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    @Test void onlyOwnerSeesOwnerInTab() {
        PlayerMock boss = new PlayerMock(server, "Owner", OWNER); server.addPlayer(boss); boss.setOp(true);
        PlayerMock other = server.addPlayer("Admin"); other.setOp(true);
        SentinelCommand cmd = new SentinelCommand(plugin);
        Command sentinel = server.getCommandMap().getCommand("sentinel");
        assertFalse(cmd.onTabComplete(boss, sentinel, "sentinel", new String[]{"ow"}).contains("owner"),
            "owner subcommand no longer exists");
        assertFalse(cmd.onTabComplete(other, sentinel, "sentinel", new String[]{"ow"}).contains("owner"),
            "owner subcommand no longer exists");
    }

    @Test void nonOwnerOwnerSubcommandDoesNotOpenPanel() {
        PlayerMock other = server.addPlayer("Admin"); other.setOp(true);
        SentinelCommand cmd = new SentinelCommand(plugin);
        cmd.onCommand(other, server.getCommandMap().getCommand("sentinel"), "sentinel", new String[]{"owner"});
        // "owner" is not a recognized subcommand, so no GUI opens — the command falls through to player lookup
        org.bukkit.inventory.InventoryView view = other.getOpenInventory();
        assertFalse(view != null && view.getTopInventory() != null
            && view.getTopInventory().getHolder() instanceof de.derfakegamer.sentinel.gui.AdminPanelGui,
            "no admin panel should open for unknown subcommand");
    }
}
