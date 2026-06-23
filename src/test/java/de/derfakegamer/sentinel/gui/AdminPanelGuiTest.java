package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import java.io.File;
import static org.junit.jupiter.api.Assertions.*;

class AdminPanelGuiTest {
    ServerMock server; Sentinel plugin;

    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    @Test void panelIsADoubleChestWithSectionButtons() {
        AdminPanelGui gui = new AdminPanelGui(plugin);
        assertEquals(54, gui.getInventory().getSize(), "the hub must be a double chest (54 slots)");
        // row 1 general (10-13), row 2 moderation (19-24), row 3 tools (28-30)
        for (int slot : new int[]{10, 11, 12, 13, 19, 20, 21, 22, 23, 24, 28, 29, 30})
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
        // Player tools row: Player Manager at slot 28, Vanish at 29, Staff Chat at 30
        assertNotNull(inv.getItem(28), "Player Manager at slot 28");
        assertNotNull(inv.getItem(29), "Vanish at slot 29");
        assertNotNull(inv.getItem(30), "Staff Chat at slot 30");

        // Slot 28 must be specifically the Player Manager button (PLAYER_HEAD, name contains "Player Manager")
        var pmItem = inv.getItem(28);
        assertEquals(Material.PLAYER_HEAD, pmItem.getType(), "slot 28 must be PLAYER_HEAD (Player Manager)");
        String pmName = PlainTextComponentSerializer.plainText().serialize(pmItem.getItemMeta().displayName());
        assertTrue(pmName.contains("Player Manager"), "slot 28 display-name must contain 'Player Manager', got: " + pmName);
    }

    @Test void clickingPlayerManagerOpensPlayersList() throws Exception {
        PlayerMock p = server.addPlayer();
        AdminPanelGui gui = new AdminPanelGui(plugin);
        gui.open(p);
        InventoryClickEvent ev = ConfirmGuiTest.clickSlot(p, gui, 28);
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
        InventoryClickEvent ev = ConfirmGuiTest.clickSlot(p, gui, 29);
        gui.onClick(ev);
        assertTrue(ev.isCancelled());
        assertNotEquals(before, plugin.vanish().isVanished(p.getUniqueId()));
    }

    @Test void clickingStaffChatTogglesStaffChat() {
        PlayerMock p = server.addPlayer();
        AdminPanelGui gui = new AdminPanelGui(plugin);
        gui.open(p);
        boolean before = plugin.staffChat().isToggled(p.getUniqueId());
        InventoryClickEvent ev = ConfirmGuiTest.clickSlot(p, gui, 30);
        gui.onClick(ev);
        assertTrue(ev.isCancelled());
        assertNotEquals(before, plugin.staffChat().isToggled(p.getUniqueId()),
            "Staff-chat state must flip after clicking slot 30");
    }

    @Test void playerManagerLabelSourcedFromMessagesYml() throws Exception {
        // Load the messages config that the plugin wrote to its data folder,
        // override the key, reload Messages, rebuild the GUI and verify the label.
        File messagesFile = new File(plugin.getDataFolder(), "messages.yml");
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(messagesFile);
        cfg.set("gui.panel.player-manager", "<gold>SENTINEL_PROOF");
        plugin.messages().reload(cfg);

        try {
            AdminPanelGui gui = new AdminPanelGui(plugin);
            String name = PlainTextComponentSerializer.plainText()
                .serialize(gui.getInventory().getItem(28).getItemMeta().displayName());
            assertTrue(name.contains("SENTINEL_PROOF"),
                "slot-28 display name must reflect the overridden messages.yml key, got: " + name);
        } finally {
            // Restore defaults so other tests are unaffected.
            plugin.messages().reload(YamlConfiguration.loadConfiguration(messagesFile));
        }
    }

    @Test void hasSelfProfileButtons() {
        AdminPanelGui gui = new AdminPanelGui(plugin);
        assertEquals(Material.NAME_TAG,    gui.getInventory().getItem(31).getType(), "Set name at 31");
        assertEquals(Material.PLAYER_HEAD, gui.getInventory().getItem(32).getType(), "Set skin at 32");
        assertEquals(Material.WATER_BUCKET, gui.getInventory().getItem(33).getType(), "Reset profile at 33");
    }

    @Test void setNameStoresOverrideForTheClickingAdmin() throws Exception {
        PlayerMock admin = server.addPlayer("Admin"); admin.setOp(true);
        AdminPanelGui gui = new AdminPanelGui(plugin);
        gui.open(admin);
        InventoryClickEvent ev = ConfirmGuiTest.clickSlot(admin, gui, 31); // Set name
        gui.onClick(ev);
        // GUI prompts for chat input; supply it the way the chat-input flow consumes it:
        plugin.chatInput().consume(admin.getUniqueId()).accept("Renamed");
        // setName writes via submitWrite; drain the executor then read back.
        plugin.db().submit(() -> null).get(2, java.util.concurrent.TimeUnit.SECONDS);
        server.getScheduler().performTicks(2);
        var dao = new de.derfakegamer.sentinel.storage.ProfileOverrideDao(plugin.db().database());
        var stored = plugin.db().submit(() -> dao.find(admin.getUniqueId()))
            .get(2, java.util.concurrent.TimeUnit.SECONDS);
        assertNotNull(stored, "an override row should exist for the admin");
        assertEquals("Renamed", stored.displayName());
    }

    @Test void hubHasAuditAndAnnouncementsButtons() {
        PlayerMock p = server.addPlayer();
        AdminPanelGui gui = new AdminPanelGui(plugin);
        gui.open(p);
        var inv = p.getOpenInventory().getTopInventory();
        assertNotNull(inv.getItem(23), "Audit Log button must be at slot 23");
        assertNotNull(inv.getItem(24), "Announcements toggle must be at slot 24");
        String auditName = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
            .serialize(inv.getItem(23).getItemMeta().displayName());
        assertTrue(auditName.contains("Audit Log"), "slot 23 must be Audit Log, got: " + auditName);
        String announceName = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
            .serialize(inv.getItem(24).getItemMeta().displayName());
        assertTrue(announceName.contains("Announcements"), "slot 24 must be Announcements, got: " + announceName);
    }
}
