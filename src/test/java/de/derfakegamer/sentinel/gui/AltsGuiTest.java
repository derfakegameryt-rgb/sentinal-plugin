package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.PlayerRecord;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class AltsGuiTest {
    ServerMock server; Sentinel plugin;

    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    @Test void showsAccountsSharingIp() throws Exception {
        PlayerMock main = server.addPlayer("Main");
        UUID altId = UUID.randomUUID();
        plugin.players().record(main.getUniqueId(), "Main", "4.4.4.4");
        plugin.players().record(altId, "AltAccount", "4.4.4.4");

        OfflinePlayer target = main;
        List<PlayerRecord> alts = plugin.players().alts(main.getUniqueId()).get(2, TimeUnit.SECONDS);
        AltsGui gui = new AltsGui(plugin, target, alts);
        int heads = 0;
        for (int i = 0; i <= 44; i++) {
            var it = gui.getInventory().getItem(i);
            if (it != null && it.getType() == Material.PLAYER_HEAD) heads++;
        }
        assertEquals(1, heads);  // the one alt (excluding the target itself)
    }
}
