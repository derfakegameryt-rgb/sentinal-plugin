package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.Note;
import de.derfakegamer.sentinel.util.Items;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
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

    /** Async opener: fetches notes then constructs and opens the GUI on the main thread. */
    public static void open(Sentinel plugin, OfflinePlayer target, Player viewer) {
        plugin.db().callback(plugin.notes().list(target.getUniqueId()),
            notes -> new NotesGui(plugin, target, notes != null ? notes : List.of()).open(viewer));
    }

    public NotesGui(Sentinel plugin, OfflinePlayer target, List<Note> notes) {
        super(plugin);
        this.target = target;
        this.inventory = Bukkit.createInventory(this, 54,
            plugin.messages().plain("gui-notes-title", "player", name()));
        for (int i = 0; i < PAGE_SIZE && i < notes.size(); i++) {
            Note n = notes.get(i);
            inventory.setItem(i, Items.button(Material.PAPER,
                Component.text(n.text(), NamedTextColor.WHITE),
                List.of(grey("By: " + n.author()),
                        grey("At: " + DATE.format(Instant.ofEpochMilli(n.createdAt()))))));
        }
        if (notes.isEmpty())
            inventory.setItem(22, Items.button(Material.BOOK,
                plugin.messages().plain("notes-empty"), List.of()));
        navBar(false, false, true);
        inventory.setItem(NAV_ACT_L1, Items.button(Material.WRITABLE_BOOK,
            Component.text("Add note", NamedTextColor.AQUA), List.of(grey("Type the note in chat"))));
    }

    private Component grey(String s) {
        return Component.text(s, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false);
    }

    private String name() { return target.getName() == null ? "?" : target.getName(); }

    @Override
    public void onClick(InventoryClickEvent event) {
        event.setCancelled(true);
        Player mod = (Player) event.getWhoClicked();
        switch (event.getRawSlot()) {
            case NAV_ACT_L1 -> {
                mod.closeInventory();
                mod.sendMessage(plugin.messages().prefixed("enter-note"));
                plugin.chatInput().await(mod.getUniqueId(), text -> {
                    plugin.notes().add(target.getUniqueId(), mod.getName(), text);
                    mod.sendMessage(plugin.messages().prefixed("note-added"));
                    NotesGui.open(plugin, target, mod);
                });
            }
            case NAV_BACK -> PlayerActionsGui.open(plugin, target, mod);
            case NAV_CLOSE -> mod.closeInventory();
        }
    }
}
