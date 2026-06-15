package de.derfakegamer.sentinel.command;

import de.derfakegamer.sentinel.Sentinel;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class SubcommandTest {
    ServerMock server; Sentinel plugin;
    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    @Test void snBanDelegatesToBan() {
        PlayerMock op = server.addPlayer("Admin"); op.setOp(true);
        PlayerMock target = server.addPlayer("Griefer");
        new SentinelCommand(plugin).onCommand(op, server.getCommandMap().getCommand("sentinel"),
            "sentinel", new String[]{"ban", "Griefer", "cheating"});
        assertNotNull(plugin.punishments().activeBan(target.getUniqueId(), System.currentTimeMillis()));
    }

    @Test void subcommandsAppearInTab() {
        PlayerMock op = server.addPlayer("Admin"); op.setOp(true);
        var out = new SentinelCommand(plugin).onTabComplete(op,
            server.getCommandMap().getCommand("sentinel"), "sentinel", new String[]{"ma"});
        assertTrue(out.contains("maintenance"));
    }
}
