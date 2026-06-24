package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.Note;
import de.derfakegamer.sentinel.util.Items;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

public final class NotesGui extends Gui {
    private static final int PAGE_SIZE = 45;
    private static final DateTimeFormatter DATE =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC);

    private final OfflinePlayer target;
    private final java.util.List<Note> notes;
    private final int page;

    /** Async opener: fetches notes then constructs and opens the GUI on the main thread. */
    public static void open(Sentinel plugin, OfflinePlayer target, Player viewer) {
        open(plugin, target, viewer, 0);
    }

    /** Async opener for a specific page. */
    public static void open(Sentinel plugin, OfflinePlayer target, Player viewer, int page) {
        plugin.db().callbackOrError(viewer, plugin.notes().list(target.getUniqueId()),
            notes -> new NotesGui(plugin, target, notes != null ? notes : List.of(), page).open(viewer));
    }

    public NotesGui(Sentinel plugin, OfflinePlayer target, List<Note> notes, int page) {
        super(plugin);
        this.target = target;
        this.notes = notes;
        this.page = Math.max(0, page);
        this.inventory = Bukkit.createInventory(this, 54,
            plugin.messages().plain("gui-notes-title", "player", name()));
        int from = this.page * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE && from + i < notes.size(); i++) {
            Note n = notes.get(from + i);
            inventory.setItem(i, Items.button(Material.PAPER,
                Component.text(n.text(), NamedTextColor.WHITE),
                plugin.messages().list("gui.notes.entry-lore",
                    "author", n.author(),
                    "date", DATE.format(Instant.ofEpochMilli(n.createdAt())))));
        }
        if (notes.isEmpty())
            inventory.setItem(22, Items.button(Material.BOOK,
                plugin.messages().plain("notes-empty"), List.of()));
        navBar(this.page > 0, from + PAGE_SIZE < notes.size(), true);
        inventory.setItem(NAV_ACT_L1, Items.button(Material.WRITABLE_BOOK,
            plugin.messages().plain("gui.notes.add"),
            plugin.messages().list("gui.notes.add-lore")));
    }

    private String name() { return target.getName() == null ? "?" : target.getName(); }

    @Override
    public void onClick(InventoryClickEvent event) {
        event.setCancelled(true);
        Player mod = (Player) event.getWhoClicked();
        int slot = event.getRawSlot();
        int from = page * PAGE_SIZE;
        if (slot >= 0 && slot < PAGE_SIZE && from + slot < notes.size()) {
            if (event.isShiftClick()) {
                plugin.notes().delete(notes.get(from + slot).id());
                mod.sendMessage(plugin.messages().prefixed("note-deleted"));
                NotesGui.open(plugin, target, mod, page); // stay on the current page
            }
            return; // plain click on a note: no-op
        }
        switch (slot) {
            case NAV_PREV -> NotesGui.open(plugin, target, mod, page - 1);
            case NAV_NEXT -> NotesGui.open(plugin, target, mod, page + 1);
            case NAV_ACT_L1 -> {
                mod.closeInventory();
                mod.sendMessage(plugin.messages().prefixed("enter-note"));
                plugin.chatInput().await(mod.getUniqueId(), text -> {
                    plugin.notes().add(target.getUniqueId(), mod.getName(), text);
                    mod.sendMessage(plugin.messages().prefixed("note-added"));
                    NotesGui.open(plugin, target, mod); // newest note is on page 0
                });
            }
            case NAV_BACK -> PlayerActionsGui.open(plugin, target, mod);
            case NAV_CLOSE -> mod.closeInventory();
        }
    }
}
