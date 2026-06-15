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
    private static final int BACK = 45, CLOSE = 53;
    private static final DateTimeFormatter DATE =
        DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneOffset.UTC);

    private final OfflinePlayer target;

    public ChatLogGui(Sentinel plugin, OfflinePlayer target) {
        super(plugin);
        this.target = target;
        this.inventory = Bukkit.createInventory(this, 54, plugin.messages().plain("gui-chatlog-title", "player", name()));
        List<ChatLogEntry> entries = plugin.chatLog().recent(target.getUniqueId(), 45);
        for (int i = 0; i < entries.size() && i < 45; i++) {
            ChatLogEntry e = entries.get(i);
            boolean cmd = e.kind().equals("COMMAND");
            inventory.setItem(i, Items.button(cmd ? Material.COMMAND_BLOCK : Material.PAPER,
                Component.text(e.text(), cmd ? NamedTextColor.YELLOW : NamedTextColor.WHITE),
                List.of(grey(e.kind() + " · " + DATE.format(Instant.ofEpochMilli(e.createdAt()))))));
        }
        inventory.setItem(BACK, Items.button(Material.ARROW, Component.text("Back", NamedTextColor.GRAY), List.of()));
        inventory.setItem(CLOSE, Items.button(Material.BARRIER, Component.text("Close", NamedTextColor.RED), List.of()));
        fillEmpty();
    }

    private Component grey(String s) { return Component.text(s, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false); }
    private String name() { return target.getName() == null ? "?" : target.getName(); }

    @Override
    public void onClick(InventoryClickEvent event) {
        event.setCancelled(true);
        Player p = (Player) event.getWhoClicked();
        if (event.getRawSlot() == BACK) new PlayerActionsGui(plugin, target).open(p);
        else if (event.getRawSlot() == CLOSE) p.closeInventory();
    }
}
