package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.Punishment;
import de.derfakegamer.sentinel.model.PunishmentType;
import org.bukkit.Material;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ActiveBansGuiTest {
    ServerMock server; Sentinel plugin;

    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    @Test void listsActiveBans() throws Exception {
        plugin.punishments().ban(UUID.randomUUID(), "Banned1", new UUID(0,0), "Admin", "x", 0).get(2, TimeUnit.SECONDS);
        plugin.punishments().ban(UUID.randomUUID(), "Banned2", new UUID(0,0), "Admin", "x", 0).get(2, TimeUnit.SECONDS);
        List<Punishment> bans = plugin.punishments().activeList(PunishmentType.BAN, System.currentTimeMillis()).get(2, TimeUnit.SECONDS);
        ActiveBansGui gui = new ActiveBansGui(plugin, bans, 0);
        int items = 0;
        for (int i = 0; i <= 44; i++) {
            var it = gui.getInventory().getItem(i);
            if (it != null && it.getType() == Material.PLAYER_HEAD) items++;
        }
        assertEquals(2, items);
    }
}
