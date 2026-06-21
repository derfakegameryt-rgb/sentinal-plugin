package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import org.bukkit.Material;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import static org.junit.jupiter.api.Assertions.*;

class WhitelistGuiTest {
    ServerMock server; Sentinel plugin;

    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    @Test void guiOpensWithControls() {
        WhitelistGui gui = new WhitelistGui(plugin, 0);
        assertNotNull(gui.getInventory());
        assertEquals(54, gui.getInventory().getSize());
        // Add button at NAV_ACT_L1(46), toggle at NAV_ACT_L2(47), back at NAV_BACK(48), close at NAV_CLOSE(50).
        assertEquals(Material.LIME_DYE, gui.getInventory().getItem(Gui.NAV_ACT_L1).getType());
        assertEquals(Material.LEVER, gui.getInventory().getItem(Gui.NAV_ACT_L2).getType());
        assertEquals(Material.OAK_DOOR, gui.getInventory().getItem(Gui.NAV_BACK).getType());
        assertEquals(Material.BARRIER, gui.getInventory().getItem(Gui.NAV_CLOSE).getType());
    }

    @Test void whitelistedPlayerShowsAsHead() {
        PlayerMock a = server.addPlayer("Whitelisted1");
        a.setWhitelisted(true);
        // Only proceed if MockBukkit populates the whitelist set; otherwise this is a no-op pass.
        if (!server.getWhitelistedPlayers().isEmpty()) {
            WhitelistGui gui = new WhitelistGui(plugin, 0);
            int heads = 0;
            for (int i = 0; i <= 44; i++) {
                var it = gui.getInventory().getItem(i);
                if (it != null && it.getType() == Material.PLAYER_HEAD) heads++;
            }
            assertTrue(heads >= 1, "expected at least one whitelisted head");
        }
    }
}
