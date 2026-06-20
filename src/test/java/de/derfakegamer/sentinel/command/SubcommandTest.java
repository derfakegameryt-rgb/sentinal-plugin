package de.derfakegamer.sentinel.command;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.gui.AdminPanelGui;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class SubcommandTest {
    ServerMock server; Sentinel plugin;
    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    private void drain() throws InterruptedException {
        for (int i = 0; i < 5; i++) {
            server.getScheduler().performTicks(1);
            Thread.sleep(50);
        }
    }

    @Test void snBanDelegatesToBan() throws Exception {
        PlayerMock op = server.addPlayer("Admin"); op.setOp(true);
        PlayerMock target = server.addPlayer("Griefer");
        new SentinelCommand(plugin).onCommand(op, server.getCommandMap().getCommand("sentinel"),
            "sentinel", new String[]{"ban", "Griefer", "cheating"});
        drain();
        assertNotNull(plugin.punishments().activeBan(target.getUniqueId(), System.currentTimeMillis()).get(2, TimeUnit.SECONDS));
    }

    @Test void snNoArgOpensHub() throws Exception {
        PlayerMock p = server.addPlayer("HubAdmin"); p.setOp(true);
        server.dispatchCommand(p, "sn");
        drain();
        assertInstanceOf(AdminPanelGui.class, p.getOpenInventory().getTopInventory().getHolder());
    }

    @Test void subcommandsAppearInTab() {
        PlayerMock op = server.addPlayer("Admin"); op.setOp(true);
        var out = new SentinelCommand(plugin).onTabComplete(op,
            server.getCommandMap().getCommand("sentinel"), "sentinel", new String[]{"ma"});
        assertTrue(out.contains("maintenance"));
    }
}
