package de.derfakegamer.sentinel.manager;

import de.derfakegamer.sentinel.Sentinel;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import static org.junit.jupiter.api.Assertions.*;

class StaffChatManagerTest {
    ServerMock server; Sentinel plugin;

    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    @Test void toggleFlipsState() {
        PlayerMock p = server.addPlayer("Mod");
        assertTrue(plugin.staffChat().toggle(p.getUniqueId()));   // now on
        assertTrue(plugin.staffChat().isToggled(p.getUniqueId()));
        assertFalse(plugin.staffChat().toggle(p.getUniqueId()));  // now off
        assertFalse(plugin.staffChat().isToggled(p.getUniqueId()));
    }

    @Test void sendReachesOnlineOps() {
        PlayerMock op = server.addPlayer("Admin");
        op.setOp(true);
        plugin.staffChat().send("Mod", "hello team");
        net.kyori.adventure.text.Component msg = op.nextComponentMessage();
        assertNotNull(msg);
        assertTrue(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
            .serialize(msg).contains("hello team"));
    }
}
