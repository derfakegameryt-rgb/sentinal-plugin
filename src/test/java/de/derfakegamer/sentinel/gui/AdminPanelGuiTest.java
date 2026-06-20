package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import static org.junit.jupiter.api.Assertions.*;

class AdminPanelGuiTest {
    ServerMock server; Sentinel plugin;

    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    @Test void panelHasTheFiveSections() {
        AdminPanelGui gui = new AdminPanelGui(plugin);
        for (int slot : new int[]{10, 11, 12, 13, 14})
            assertNotNull(gui.getInventory().getItem(slot), "section button at " + slot);
    }

    @Test void serverInfoOpensFromPanel() {
        PlayerMock op = server.addPlayer("Admin"); op.setOp(true);
        AdminPanelGui gui = new AdminPanelGui(plugin);
        gui.open(op);
        InventoryClickEvent ev = ConfirmGuiTest.clickSlot(op, gui, 10); // Server Info
        gui.onClick(ev);
        assertTrue(ev.isCancelled());
        assertInstanceOf(ServerInfoGui.class, op.getOpenInventory().getTopInventory().getHolder());
    }

    @Test void hubHasPlayerManagerVanishStaffChat() {
        PlayerMock p = server.addPlayer();
        AdminPanelGui gui = new AdminPanelGui(plugin);
        gui.open(p);
        var inv = p.getOpenInventory().getTopInventory();
        // Player Manager at slot 19, Vanish at 20, Staff Chat at 21
        assertNotNull(inv.getItem(19), "Player Manager at slot 19");
        assertNotNull(inv.getItem(20), "Vanish at slot 20");
        assertNotNull(inv.getItem(21), "Staff Chat at slot 21");

        // Slot 19 must be specifically the Player Manager button (PLAYER_HEAD, name contains "Player Manager")
        var pmItem = inv.getItem(19);
        assertEquals(Material.PLAYER_HEAD, pmItem.getType(), "slot 19 must be PLAYER_HEAD (Player Manager)");
        String pmName = PlainTextComponentSerializer.plainText().serialize(pmItem.getItemMeta().displayName());
        assertTrue(pmName.contains("Player Manager"), "slot 19 display-name must contain 'Player Manager', got: " + pmName);
    }

    @Test void clickingPlayerManagerOpensPlayersList() throws Exception {
        PlayerMock p = server.addPlayer();
        AdminPanelGui gui = new AdminPanelGui(plugin);
        gui.open(p);
        InventoryClickEvent ev = ConfirmGuiTest.clickSlot(p, gui, 19);
        gui.onClick(ev);
        // PlayersGui.open is async (DB-backed) -> drain + tick
        plugin.db().submit(() -> null).get(2, java.util.concurrent.TimeUnit.SECONDS);
        server.getScheduler().performTicks(2);
        assertTrue(ev.isCancelled());
        assertInstanceOf(PlayersGui.class, p.getOpenInventory().getTopInventory().getHolder());
    }

    @Test void clickingVanishTogglesVanish() {
        PlayerMock p = server.addPlayer();
        AdminPanelGui gui = new AdminPanelGui(plugin);
        gui.open(p);
        boolean before = plugin.vanish().isVanished(p.getUniqueId());
        InventoryClickEvent ev = ConfirmGuiTest.clickSlot(p, gui, 20);
        gui.onClick(ev);
        assertTrue(ev.isCancelled());
        assertNotEquals(before, plugin.vanish().isVanished(p.getUniqueId()));
    }

    @Test void clickingStaffChatTogglesStaffChat() {
        PlayerMock p = server.addPlayer();
        AdminPanelGui gui = new AdminPanelGui(plugin);
        gui.open(p);
        boolean before = plugin.staffChat().isToggled(p.getUniqueId());
        InventoryClickEvent ev = ConfirmGuiTest.clickSlot(p, gui, 21);
        gui.onClick(ev);
        assertTrue(ev.isCancelled());
        assertNotEquals(before, plugin.staffChat().isToggled(p.getUniqueId()),
            "Staff-chat state must flip after clicking slot 21");
    }

    @Test void hubHasAuditAndModStatsButtons() {
        PlayerMock p = server.addPlayer();
        AdminPanelGui gui = new AdminPanelGui(plugin);
        gui.open(p);
        var inv = p.getOpenInventory().getTopInventory();
        assertNotNull(inv.getItem(23), "Audit Log button must be at slot 23");
        assertNotNull(inv.getItem(24), "Mod Stats button must be at slot 24");
        String auditName = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
            .serialize(inv.getItem(23).getItemMeta().displayName());
        assertTrue(auditName.contains("Audit Log"), "slot 23 must be Audit Log, got: " + auditName);
        String modStatsName = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
            .serialize(inv.getItem(24).getItemMeta().displayName());
        assertTrue(modStatsName.contains("Mod Stats"), "slot 24 must be Mod Stats, got: " + modStatsName);
    }
}
