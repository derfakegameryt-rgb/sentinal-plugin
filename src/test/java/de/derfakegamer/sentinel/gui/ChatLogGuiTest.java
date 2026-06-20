package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import org.bukkit.Material;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ChatLogGuiTest {
    ServerMock server;
    Sentinel plugin;

    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    @Test void rendersLoggedMessages() throws Exception {
        PlayerMock t = server.addPlayer("Bob");
        PlayerMock viewer = server.addPlayer("Mod");

        plugin.chatLog().logChat(t.getUniqueId(), "Bob", "hello world");
        plugin.chatLog().logCommand(t.getUniqueId(), "Bob", "/help");

        // Open via static opener: fires recent() -> DB thread -> whenComplete -> Bukkit scheduler
        ChatLogGui.open(plugin, t, viewer);

        // Drain the DB executor — by the time this returns, the recent() inside open() has completed
        // and whenComplete has already scheduled the GUI construction on the Bukkit main-thread scheduler
        plugin.db().submit(() -> null).get(2, TimeUnit.SECONDS);

        // Flush the Bukkit scheduler so the main-thread callback runs (constructs and opens the GUI)
        server.getScheduler().performTicks(1);

        // Verify the GUI opened with the expected items
        var inv = viewer.getOpenInventory().getTopInventory();
        assertNotNull(inv);
        assertInstanceOf(ChatLogGui.class, inv.getHolder());

        int items = 0;
        for (int i = 0; i <= 44; i++) {
            var it = inv.getItem(i);
            if (it != null && (it.getType() == Material.PAPER || it.getType() == Material.COMMAND_BLOCK)) items++;
        }
        assertEquals(2, items);
    }
}
