package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class TemplatesGuiTest {
    ServerMock server; Sentinel plugin;

    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    /** Pumps scheduler ticks while waiting for futures that schedule main-thread side-effects. */
    static <T> T await(ServerMock server, CompletableFuture<T> f) throws Exception {
        for (int i = 0; i < 200 && !f.isDone(); i++) {
            server.getScheduler().performTicks(1);
            Thread.sleep(5);
        }
        return f.get(2, TimeUnit.SECONDS);
    }

    @Test void clickingTemplateAppliesPunishment() throws Exception {
        plugin.getConfig().set("templates", java.util.List.of("ban template ban reason"));
        PlayerMock mod = server.addPlayer("Mod"); mod.setOp(true);
        PlayerMock target = server.addPlayer("BadGuy");
        TemplatesGui gui = new TemplatesGui(plugin, target);
        gui.open(mod);
        InventoryClickEvent ev = ConfirmGuiTest.clickSlot(mod, gui, 0);
        gui.onClick(ev);
        // The GUI triggers moderation().apply() which now schedules a main-thread task.
        // Pump the scheduler to flush the onMain hop before asserting.
        for (int i = 0; i < 200; i++) {
            server.getScheduler().performTicks(1);
            Thread.sleep(5);
            if (plugin.punishments().activeBan(target.getUniqueId(), System.currentTimeMillis()).isDone()) break;
        }
        assertNotNull(plugin.punishments().activeBan(target.getUniqueId(), System.currentTimeMillis()).get(2, TimeUnit.SECONDS));
    }
}
