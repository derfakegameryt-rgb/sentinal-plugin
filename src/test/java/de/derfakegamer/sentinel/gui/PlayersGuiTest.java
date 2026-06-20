package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class PlayersGuiTest {
    ServerMock server; Sentinel plugin;

    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    /** Build a PlayersGui synchronously (for tests only). */
    private PlayersGui buildGui(int page) throws Exception {
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

    @Test void showsOnlinePlayersAsHeads() throws Exception {
        server.addPlayer("Alice");
        server.addPlayer("Bob");
        PlayersGui gui = buildGui(0);
        int heads = 0;
        for (int i = 0; i <= 44; i++) {
            var item = gui.getInventory().getItem(i);
            if (item != null && item.getType() == Material.PLAYER_HEAD) heads++;
        }
        assertEquals(2, heads);
    }

    @Test void clickingHeadOpensActions() throws Exception {
        PlayerMock mod = server.addPlayer("Mod");
        PlayersGui gui = buildGui(0);
        gui.open(mod);
        // find the slot holding a head (Mod is the only online player)
        int slot = -1;
        for (int i = 0; i <= 44; i++)
            if (gui.getInventory().getItem(i) != null && gui.getInventory().getItem(i).getType() == Material.PLAYER_HEAD) { slot = i; break; }
        assertTrue(slot >= 0);

        InventoryClickEvent event = ConfirmGuiTest.clickSlot(mod, gui, slot);
        gui.onClick(event);

        assertTrue(event.isCancelled());
        // PlayerActionsGui.open() is async — tick the scheduler to process the main-thread callback
        // and wait for the DB executor to complete, then tick again
        Thread.sleep(200);
        server.getScheduler().performTicks(2);
        assertInstanceOf(PlayerActionsGui.class, mod.getOpenInventory().getTopInventory().getHolder());
    }
}
