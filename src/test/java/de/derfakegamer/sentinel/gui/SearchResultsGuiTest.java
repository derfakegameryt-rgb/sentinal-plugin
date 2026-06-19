package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import org.bukkit.Material;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import static org.junit.jupiter.api.Assertions.*;

class SearchResultsGuiTest {
    ServerMock server; Sentinel plugin;

    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    @Test void findsOnlinePlayerByPartialName() {
        server.addPlayer("Notch");
        server.addPlayer("Alex");
        SearchResultsGui gui = new SearchResultsGui(plugin, "not");
        int heads = 0;
        for (int i = 0; i <= 44; i++) {
            var it = gui.getInventory().getItem(i);
            if (it != null && it.getType() == Material.PLAYER_HEAD) heads++;
        }
        assertEquals(1, heads); // only Notch matches "not"
    }

    @Test void findsStoredOfflinePlayer() {
        java.util.UUID id = java.util.UUID.randomUUID();
        plugin.players().record(id, "OfflineGuy", "1.2.3.4");
        SearchResultsGui gui = new SearchResultsGui(plugin, "offlineguy");
        int heads = 0;
        for (int i = 0; i <= 44; i++) {
            var it = gui.getInventory().getItem(i);
            if (it != null && it.getType() == Material.PLAYER_HEAD) heads++;
        }
        assertEquals(1, heads);
    }
}
