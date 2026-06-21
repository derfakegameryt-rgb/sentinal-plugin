package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class GuiLayoutTest {
    ServerMock server; Sentinel plugin;
    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    /** Build a PlayersGui synchronously (for tests only). */
    private PlayersGui buildPlayersGui(int page) throws Exception {
        List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
        players.sort(java.util.Comparator.comparing(
            p -> p.getName() != null ? p.getName() : p.getUniqueId().toString(),
            String.CASE_INSENSITIVE_ORDER));
        int from = page * 45;
        int count = Math.min(45, players.size() - from);
        boolean[] muted = new boolean[count];
        int[] warns = new int[count];
        long now = System.currentTimeMillis();
        for (int i = 0; i < count; i++) {
            Player p = players.get(from + i);
            muted[i] = plugin.punishments().activeMute(p.getUniqueId(), now).get(2, TimeUnit.SECONDS) != null;
            warns[i] = plugin.punishments().warnCount(p.getUniqueId()).get(2, TimeUnit.SECONDS);
        }
        int reportCount = plugin.reports().open().get(2, TimeUnit.SECONDS).size();
        return new PlayersGui(plugin, page, players, muted, warns, reportCount);
    }

    @Test void menuGuiHasAccentBorder() {
        AdminPanelGui gui = new AdminPanelGui(plugin);
        // corners of the 54-slot menu are the blue accent border
        for (int slot : new int[]{0, 8, 45, 53})
            assertEquals(Material.LIGHT_BLUE_STAINED_GLASS_PANE, gui.getInventory().getItem(slot).getType());
    }

    @Test void listGuiHasAccentControlBarAndBlackContentFiller() throws Exception {
        server.addPlayer("Solo");                 // few players -> content slots beyond the head are empty
        PlayersGui gui = buildPlayersGui(0);
        // empty control-row slot (49, between Back@48 and Close@50) = accent
        assertEquals(Material.LIGHT_BLUE_STAINED_GLASS_PANE, gui.getInventory().getItem(49).getType());
        // empty content slot (44, high in the 0-44 content area) = black filler
        assertEquals(Material.BLACK_STAINED_GLASS_PANE, gui.getInventory().getItem(44).getType());
    }

    @Test void playersListIsSorted() throws Exception {
        server.addPlayer("Zebra");
        server.addPlayer("alpha");
        PlayersGui gui = buildPlayersGui(0);
        // first head should be "alpha" (case-insensitive sort)
        var first = gui.getInventory().getItem(0);
        assertNotNull(first);
        assertEquals(Material.PLAYER_HEAD, first.getType());
        assertTrue(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
            .serialize(first.getItemMeta().displayName()).equalsIgnoreCase("alpha"));
    }
}
