package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.OrbitalPayload;
import de.derfakegamer.sentinel.model.ScheduledStrike;
import org.bukkit.Material;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ScheduledStrikesGuiTest {
    ServerMock server;
    Sentinel plugin;

    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    /** Helper: drain DB executor then flush Bukkit scheduler. */
    private void drainAndTick() throws Exception {
        plugin.db().submit(() -> null).get(2, TimeUnit.SECONDS);
        server.getScheduler().performTicks(1);
    }

    @Test void staticOpenerOpenGuiWithStrikeItems() throws Exception {
        PlayerMock p = server.addPlayer("Admin"); p.setOp(true);
        long fireAt = System.currentTimeMillis() + 60_000;
        plugin.scheduledStrikes()
            .schedule(server.addSimpleWorld("world"), 10, 20, OrbitalPayload.TNT, fireAt)
            .get(2, TimeUnit.SECONDS);

        ScheduledStrikesGui.open(plugin, p);
        drainAndTick();

        var inv = p.getOpenInventory().getTopInventory();
        assertInstanceOf(ScheduledStrikesGui.class, inv.getHolder());
        // Slot 0 should have a CLOCK for the strike
        assertNotNull(inv.getItem(0));
        assertEquals(Material.CLOCK, inv.getItem(0).getType());
    }

    @Test void staticOpenerWithNoStrikesOpensEmptyGui() throws Exception {
        PlayerMock p = server.addPlayer("Admin"); p.setOp(true);

        ScheduledStrikesGui.open(plugin, p);
        drainAndTick();

        assertInstanceOf(ScheduledStrikesGui.class, p.getOpenInventory().getTopInventory().getHolder());
    }

    @Test void constructorWithPreFetchedList() {
        PlayerMock p = server.addPlayer("Admin"); p.setOp(true);
        long fireAt = System.currentTimeMillis() + 60_000;
        List<ScheduledStrike> list = List.of(
            new ScheduledStrike(1L, "world", 5, 10, OrbitalPayload.TNT, fireAt));

        ScheduledStrikesGui gui = new ScheduledStrikesGui(plugin, list);
        gui.open(p);

        var inv = p.getOpenInventory().getTopInventory();
        assertInstanceOf(ScheduledStrikesGui.class, inv.getHolder());
        assertNotNull(inv.getItem(0));
        assertEquals(Material.CLOCK, inv.getItem(0).getType());
    }

    @Test void backButtonOpensOrbitalModeGui() {
        PlayerMock p = server.addPlayer("Admin"); p.setOp(true);
        ScheduledStrikesGui gui = new ScheduledStrikesGui(plugin, List.of());
        gui.open(p);

        InventoryClickEvent ev = ConfirmGuiTest.clickSlot(p, gui, 45); // BACK
        gui.onClick(ev);

        assertInstanceOf(OrbitalModeGui.class, p.getOpenInventory().getTopInventory().getHolder());
    }

    @Test void closeButtonClosesInventory() {
        PlayerMock p = server.addPlayer("Admin"); p.setOp(true);
        ScheduledStrikesGui gui = new ScheduledStrikesGui(plugin, List.of());
        gui.open(p);

        InventoryClickEvent ev = ConfirmGuiTest.clickSlot(p, gui, 53); // CLOSE
        gui.onClick(ev);

        // After closeInventory(), no ScheduledStrikesGui holder (may be null or a different inventory)
        var top = p.getOpenInventory().getTopInventory();
        assertFalse(top != null && top.getHolder() instanceof ScheduledStrikesGui);
    }

    @Test void cancelClickRemovesStrikeAndRefreshes() throws Exception {
        PlayerMock p = server.addPlayer("Admin"); p.setOp(true);
        long fireAt = System.currentTimeMillis() + 60_000;
        long id = plugin.scheduledStrikes()
            .schedule(server.addSimpleWorld("world"), 1, 2, OrbitalPayload.TNT, fireAt)
            .get(2, TimeUnit.SECONDS);

        List<ScheduledStrike> list = plugin.scheduledStrikes().pending().get(2, TimeUnit.SECONDS);
        ScheduledStrikesGui gui = new ScheduledStrikesGui(plugin, list);
        gui.open(p);

        // Click slot 0 to cancel the strike
        InventoryClickEvent ev = ConfirmGuiTest.clickSlot(p, gui, 0);
        gui.onClick(ev);

        // cancel() -> DB executor -> callback -> refresh GUI via static opener
        drainAndTick();  // drain cancel future
        drainAndTick();  // drain pending() for refresh

        // After refresh, new ScheduledStrikesGui should be open
        assertInstanceOf(ScheduledStrikesGui.class, p.getOpenInventory().getTopInventory().getHolder());

        // DB should have no more pending strikes
        List<ScheduledStrike> remaining = plugin.scheduledStrikes().pending().get(2, TimeUnit.SECONDS);
        assertTrue(remaining.isEmpty());
    }
}
