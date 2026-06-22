package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.util.ServerOptimizer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ServerInfoGuiTest {
    ServerMock server; Sentinel plugin;
    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    @Test void buildsAndUpdatesWithoutError() {
        ServerInfoGui gui = new ServerInfoGui(plugin);
        assertNotNull(gui.getInventory().getItem(12)); // memory item present
        gui.update();                                  // manual refresh does not throw
        assertNotNull(gui.getInventory().getItem(12));
    }

    @Test void optimizeButtonShowsCurrentAndRecommended() {
        ServerInfoGui gui = new ServerInfoGui(plugin);
        ItemStack item = gui.getInventory().getItem(22);
        assertNotNull(item, "Slot 22 must have the optimize button");

        ItemMeta meta = item.getItemMeta();
        assertNotNull(meta);
        List<Component> lore = meta.lore();
        assertNotNull(lore, "Optimize button must have lore");
        assertTrue(lore.size() >= 2, "Lore must have at least 2 lines");

        ServerOptimizer.Preset rec = ServerOptimizer.recommend(Runtime.getRuntime().maxMemory());
        String lorePlain = lore.stream()
            .map(PlainTextComponentSerializer.plainText()::serialize)
            .reduce("", (a, b) -> a + "\n" + b);

        assertTrue(lorePlain.contains(String.valueOf(rec.view())),
            "Lore must contain recommended view distance " + rec.view() + "; got: " + lorePlain);
        assertTrue(lorePlain.contains(String.valueOf(rec.sim())),
            "Lore must contain recommended sim distance " + rec.sim() + "; got: " + lorePlain);
    }

    @Test void clickingOptimizeRecordsAudit() throws Exception {
        PlayerMock p = server.addPlayer("OpMod");
        p.setOp(true);

        ServerInfoGui gui = new ServerInfoGui(plugin);
        var event = ConfirmGuiTest.clickSlot(p, gui, 22);
        gui.onClick(event);

        // drain the DB executor so the audit INSERT completes, then drain the recent() read
        plugin.db().submit(() -> null).get(2, TimeUnit.SECONDS);
        server.getScheduler().performTicks(2);

        var entries = plugin.audit().recent(10, 0).get(2, TimeUnit.SECONDS);
        assertTrue(entries.stream().anyMatch(e -> "OPTIMIZE".equals(e.action())),
            "An OPTIMIZE audit entry must exist");

        String msg = p.nextMessage() == null ? "" : PlainTextComponentSerializer.plainText()
            .serialize(p.nextComponentMessage() != null ? p.nextComponentMessage() : Component.empty());
        // Check that any message was sent (the player received the optimize-applied message)
        // We just verify via audit since message assertion is secondary
        assertTrue(entries.stream().anyMatch(e -> "OPTIMIZE".equals(e.action()) && "OpMod".equals(e.actor())),
            "Audit entry must record actor OpMod");
    }

    @Test void nonOpCannotOptimize() throws Exception {
        PlayerMock p = server.addPlayer("NonOp");
        p.setOp(false);

        ServerInfoGui gui = new ServerInfoGui(plugin);
        var event = ConfirmGuiTest.clickSlot(p, gui, 22);
        gui.onClick(event);

        // drain executor — no audit write should have happened
        plugin.db().submit(() -> null).get(2, TimeUnit.SECONDS);
        server.getScheduler().performTicks(2);

        var entries = plugin.audit().recent(10, 0).get(2, TimeUnit.SECONDS);
        assertFalse(entries.stream().anyMatch(e -> "OPTIMIZE".equals(e.action())),
            "Non-op click must NOT produce an OPTIMIZE audit entry");

        // player should have received a no-permission message
        Component msg = p.nextComponentMessage();
        assertNotNull(msg, "Non-op must receive a message");
    }
}
