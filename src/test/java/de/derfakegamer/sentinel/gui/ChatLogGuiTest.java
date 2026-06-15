package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import org.bukkit.Material;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import static org.junit.jupiter.api.Assertions.*;

class ChatLogGuiTest {
    ServerMock server; Sentinel plugin;

    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    @Test void rendersLoggedMessages() {
        PlayerMock t = server.addPlayer("Bob");
        plugin.chatLog().logChat(t.getUniqueId(), "Bob", "hello world");
        plugin.chatLog().logCommand(t.getUniqueId(), "Bob", "/help");
        ChatLogGui gui = new ChatLogGui(plugin, t);
        int items = 0;
        for (int i = 0; i <= 44; i++) {
            var it = gui.getInventory().getItem(i);
            if (it != null && (it.getType() == Material.PAPER || it.getType() == Material.COMMAND_BLOCK)) items++;
        }
        assertEquals(2, items);
    }
}
