package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import org.bukkit.Material;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import static org.junit.jupiter.api.Assertions.*;

class OperatorsGuiTest {
    ServerMock server; Sentinel plugin;

    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    @Test void listsOperatorsAsHeads() {
        PlayerMock a = server.addPlayer("Admin1"); a.setOp(true);
        PlayerMock b = server.addPlayer("Admin2"); b.setOp(true);
        server.addPlayer("Normal");
        OperatorsGui gui = new OperatorsGui(plugin, 0);
        int heads = 0;
        for (int i = 0; i <= 44; i++) {
            var it = gui.getInventory().getItem(i);
            if (it != null && it.getType() == Material.PLAYER_HEAD) heads++;
        }
        assertEquals(2, heads);
    }
}
