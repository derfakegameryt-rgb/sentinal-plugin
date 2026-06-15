package de.derfakegamer.sentinel.command;

import de.derfakegamer.sentinel.Sentinel;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import static org.junit.jupiter.api.Assertions.*;

class ClearChatCommandTest {
    ServerMock server; Sentinel plugin;

    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    @Test void opCanClearChat() {
        PlayerMock op = server.addPlayer("Admin"); op.setOp(true);
        boolean handled = server.dispatchCommand(op, "clearchat");
        assertTrue(handled);
    }

    @Test void nonOpIsRejected() {
        PlayerMock p = server.addPlayer("Player");
        ClearChatCommand cmd = new ClearChatCommand(plugin);
        cmd.onCommand(p, server.getCommandMap().getCommand("clearchat"), "clearchat", new String[0]);
        // a non-op should get the no-permission message and no exception
        assertNotNull(p.nextMessage());
    }
}
