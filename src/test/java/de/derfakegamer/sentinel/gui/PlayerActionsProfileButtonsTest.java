package de.derfakegamer.sentinel.gui;

import static org.junit.jupiter.api.Assertions.*;

import de.derfakegamer.sentinel.Sentinel;
import org.bukkit.Material;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class PlayerActionsProfileButtonsTest {
    ServerMock server;
    Sentinel plugin;

    @BeforeEach
    void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }

    @AfterEach
    void teardown() { MockBukkit.unmock(); }

    @Test
    void onlineTargetShowsProfileButtons() {
        PlayerMock target = server.addPlayer("Target");
        PlayerActionsGui gui = new PlayerActionsGui(plugin, target, false, false, false, 0, "1.2.3.4");
        // slots 28/29/30 are set-name, set-skin, reset-profile
        assertEquals(Material.NAME_TAG, gui.getInventory().getItem(28).getType());
        assertEquals(Material.PLAYER_HEAD, gui.getInventory().getItem(29).getType());
        assertEquals(Material.WATER_BUCKET, gui.getInventory().getItem(30).getType());
    }

    @Test
    void offlineTargetHidesProfileButtons() {
        org.bukkit.OfflinePlayer target = server.getOfflinePlayer("Ghost");
        PlayerActionsGui gui = new PlayerActionsGui(plugin, target, false, false, false, 0, null);
        assertNotEquals(Material.NAME_TAG, gui.getInventory().getItem(28).getType());
    }
}
