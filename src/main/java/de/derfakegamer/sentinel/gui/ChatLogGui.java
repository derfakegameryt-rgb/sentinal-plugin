package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.ChatLogEntry;
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

public final class ChatLogGui extends Gui {
    private static final DateTimeFormatter DATE =
        DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneOffset.UTC);

    private final OfflinePlayer target;

    /** Async opener: fetches recent entries off the main thread, then constructs and opens the GUI on the main thread. */
    public static void open(Sentinel plugin, OfflinePlayer target, Player viewer) {
        plugin.db().callback(plugin.chatLog().recent(target.getUniqueId(), 45),
            entries -> new ChatLogGui(plugin, target, entries).open(viewer));
    }

    public ChatLogGui(Sentinel plugin, OfflinePlayer target, List<ChatLogEntry> entries) {
        super(plugin);
        this.target = target;
        this.inventory = Bukkit.createInventory(this, 54, plugin.messages().plain("gui-chatlog-title", "player", name()));
        for (int i = 0; i < entries.size() && i < 45; i++) {
            ChatLogEntry e = entries.get(i);
            boolean cmd = e.kind().equals("COMMAND");
            inventory.setItem(i, Items.button(cmd ? Material.COMMAND_BLOCK : Material.PAPER,
                Component.text(e.text(), cmd ? NamedTextColor.YELLOW : NamedTextColor.WHITE),
                List.of(grey(e.kind() + " · " + DATE.format(Instant.ofEpochMilli(e.createdAt()))))));
        }
        navBar(false, false, true);
        fillEmpty();
    }

    private Component grey(String s) { return Component.text(s, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false); }
    private String name() { return target.getName() == null ? "?" : target.getName(); }

    @Override
    public void onClick(InventoryClickEvent event) {
        event.setCancelled(true);
        Player p = (Player) event.getWhoClicked();
        if (event.getRawSlot() == Gui.NAV_BACK) PlayerActionsGui.open(plugin, target, p);
        else if (event.getRawSlot() == Gui.NAV_CLOSE) p.closeInventory();
    }
}
