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
        NotesGui gui = new NotesGui(plugin, target, notes);
        int count = 0;
        for (int i = 0; i <= 44; i++) {
            var it = gui.getInventory().getItem(i);
            if (it != null && it.getType() == Material.PAPER) count++;
        }
        assertEquals(2, count);
    }

    @Test void addButtonAwaitsChatInput() throws Exception {
        PlayerMock mod = server.addPlayer("Mod");
        OfflinePlayer target = server.addPlayer("Suspect");
        List<de.derfakegamer.sentinel.model.Note> notes =
            plugin.notes().list(target.getUniqueId()).get(2, TimeUnit.SECONDS);
        NotesGui gui = new NotesGui(plugin, target, notes);
        gui.open(mod);
        // the add-note button is at slot 49
        org.bukkit.event.inventory.InventoryClickEvent ev = ConfirmGuiTest.clickSlot(mod, gui, 49);
        gui.onClick(ev);
        assertTrue(ev.isCancelled());
        assertTrue(plugin.chatInput().has(mod.getUniqueId()), "clicking add awaits a chat note");
    }
}
