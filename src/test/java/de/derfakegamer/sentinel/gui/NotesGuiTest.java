package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import org.bukkit.Material;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.bukkit.OfflinePlayer;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class NotesGuiTest {
    ServerMock server; Sentinel plugin;

    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    @Test void rendersOneItemPerNote() throws Exception {
        OfflinePlayer target = server.addPlayer("Suspect");
        plugin.notes().add(target.getUniqueId(), "Admin", "warned for spam");
        plugin.notes().add(target.getUniqueId(), "Mod", "rude in chat");
        // Drain the executor so both inserts are committed before we list
        List<de.derfakegamer.sentinel.model.Note> notes =
            plugin.notes().list(target.getUniqueId()).get(2, TimeUnit.SECONDS);
        NotesGui gui = new NotesGui(plugin, target, notes, 0);
        int count = 0;
        for (int i = 0; i <= 44; i++) {
            var it = gui.getInventory().getItem(i);
            if (it != null && it.getType() == Material.PAPER) count++;
        }
        assertEquals(2, count);
    }

    @Test void shiftClickDeletesNote() throws Exception {
        PlayerMock mod = server.addPlayer("Mod"); mod.setOp(true);
        org.bukkit.OfflinePlayer target = server.getOfflinePlayer("Griefer");
        plugin.notes().add(target.getUniqueId(), "Mod", "bad behaviour");
        plugin.db().submit(() -> null).get(2, java.util.concurrent.TimeUnit.SECONDS); // let the write land
        java.util.List<de.derfakegamer.sentinel.model.Note> notes =
            plugin.notes().list(target.getUniqueId()).get(2, java.util.concurrent.TimeUnit.SECONDS);
        NotesGui gui = new NotesGui(plugin, target, notes, 0);
        gui.open(mod);
        org.bukkit.event.inventory.InventoryClickEvent ev = new org.bukkit.event.inventory.InventoryClickEvent(
            mod.getOpenInventory(), org.bukkit.event.inventory.InventoryType.SlotType.CONTAINER, 0,
            org.bukkit.event.inventory.ClickType.SHIFT_LEFT, org.bukkit.event.inventory.InventoryAction.PICKUP_ALL);
        gui.onClick(ev);
        plugin.db().submit(() -> null).get(2, java.util.concurrent.TimeUnit.SECONDS);
        assertTrue(plugin.notes().list(target.getUniqueId()).get(2, java.util.concurrent.TimeUnit.SECONDS).isEmpty());
    }

    @Test void shiftClickDeletesCorrectNoteOnSecondPage() throws Exception {
        PlayerMock mod = server.addPlayer("Mod"); mod.setOp(true);
        OfflinePlayer target = server.getOfflinePlayer("Spammer");
        for (int i = 0; i < 46; i++) plugin.notes().add(target.getUniqueId(), "Mod", "note-" + i);
        plugin.db().submit(() -> null).get(2, TimeUnit.SECONDS); // let all writes land
        List<de.derfakegamer.sentinel.model.Note> notes =
            plugin.notes().list(target.getUniqueId()).get(2, TimeUnit.SECONDS);
        assertEquals(46, notes.size());
        long idOnPage1 = notes.get(45).id(); // the single note that spills onto page index 1
        NotesGui gui = new NotesGui(plugin, target, notes, 1);
        gui.open(mod);
        // page index 1 shows exactly one note, at slot 0 (other content slots are filler panes)
        assertEquals(Material.PAPER, gui.getInventory().getItem(0).getType());
        int paperOnPage = 0;
        for (int i = 0; i <= 44; i++) {
            var it = gui.getInventory().getItem(i);
            if (it != null && it.getType() == Material.PAPER) paperOnPage++;
        }
        assertEquals(1, paperOnPage, "page index 1 must show exactly one note");
        org.bukkit.event.inventory.InventoryClickEvent ev = new org.bukkit.event.inventory.InventoryClickEvent(
            mod.getOpenInventory(), org.bukkit.event.inventory.InventoryType.SlotType.CONTAINER, 0,
            org.bukkit.event.inventory.ClickType.SHIFT_LEFT, org.bukkit.event.inventory.InventoryAction.PICKUP_ALL);
        gui.onClick(ev);
        plugin.db().submit(() -> null).get(2, TimeUnit.SECONDS);
        List<de.derfakegamer.sentinel.model.Note> after =
            plugin.notes().list(target.getUniqueId()).get(2, TimeUnit.SECONDS);
        assertEquals(45, after.size());
        assertTrue(after.stream().noneMatch(n -> n.id() == idOnPage1),
            "shift-click on page 1 slot 0 must delete the note shown there, not a page-0 note");
    }

    @Test void addButtonAwaitsChatInput() throws Exception {
        PlayerMock mod = server.addPlayer("Mod");
        OfflinePlayer target = server.addPlayer("Suspect");
        List<de.derfakegamer.sentinel.model.Note> notes =
            plugin.notes().list(target.getUniqueId()).get(2, TimeUnit.SECONDS);
        NotesGui gui = new NotesGui(plugin, target, notes, 0);
        gui.open(mod);
        // the add-note button is at NAV_ACT_L1 (slot 46)
        org.bukkit.event.inventory.InventoryClickEvent ev = ConfirmGuiTest.clickSlot(mod, gui, Gui.NAV_ACT_L1);
        gui.onClick(ev);
        assertTrue(ev.isCancelled());
        assertTrue(plugin.chatInput().has(mod.getUniqueId()), "clicking add awaits a chat note");
    }
}
