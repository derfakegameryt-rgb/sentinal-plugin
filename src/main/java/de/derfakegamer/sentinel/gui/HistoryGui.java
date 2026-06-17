package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.Punishment;
import de.derfakegamer.sentinel.model.PunishmentType;
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

public final class HistoryGui extends Gui {
    private static final int PAGE_SIZE = 45;
    private static final int PREV = 45, BACK = 49, NEXT = 53;
    private static final DateTimeFormatter DATE =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC);

    private final OfflinePlayer target;
    private final int page;
    private final int total;

    /** Async opener: fetches history then constructs and opens the GUI on the main thread. */
    public static void open(Sentinel plugin, OfflinePlayer target, Player viewer, int page) {
        plugin.db().callback(plugin.punishments().history(target.getUniqueId()),
            all -> new HistoryGui(plugin, target, all != null ? all : List.of(), page).open(viewer));
    }

    public HistoryGui(Sentinel plugin, OfflinePlayer target, List<Punishment> all, int page) {
        super(plugin);
        this.target = target;
        this.page = page;
        this.total = all.size();
        this.inventory = Bukkit.createInventory(this, 54,
            plugin.messages().plain("gui-history-title", "player", name()));

        int from = page * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE && from + i < all.size(); i++) {
            Punishment p = all.get(from + i);
            inventory.setItem(i, Items.button(iconFor(p.type()), Component.text(p.type().name(), NamedTextColor.AQUA), List.of(
                line("Reason: " + p.reason()),
                line("By: " + p.issuerName()),
                line("Date: " + DATE.format(Instant.ofEpochMilli(p.createdAt()))),
                p.active() ? Component.text("Active", NamedTextColor.RED).decoration(TextDecoration.ITALIC, false)
                           : Component.text("Removed/expired", NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false))));
        }
        if (page > 0) inventory.setItem(PREV, Items.button(Material.ARROW, Component.text("Previous", NamedTextColor.GRAY),
            List.of(line("Go to the previous page"))));
        inventory.setItem(BACK, Items.button(Material.BARRIER, Component.text("Back", NamedTextColor.RED),
            List.of(line("Return to player actions"))));
        if (from + PAGE_SIZE < total) inventory.setItem(NEXT, Items.button(Material.ARROW, Component.text("Next", NamedTextColor.GRAY),
            List.of(line("Go to the next page"))));
        fillEmpty();
    }

    private static Component line(String text) {
        return Component.text(text, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false);
    }

    private String name() { return target.getName() == null ? "?" : target.getName(); }

    private Material iconFor(PunishmentType type) {
        return switch (type) {
            case BAN, IPBAN -> Material.RED_WOOL;
            case MUTE       -> Material.BOOK;
            case WARN       -> Material.YELLOW_BANNER;
            case KICK       -> Material.LEATHER_BOOTS;
            case SHADOWMUTE -> Material.BOOK;
        };
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        event.setCancelled(true);
        Player mod = (Player) event.getWhoClicked();
        switch (event.getRawSlot()) {
            case PREV -> open(plugin, target, mod, page - 1);
            case NEXT -> open(plugin, target, mod, page + 1);
            case BACK -> new PlayerActionsGui(plugin, target).open(mod);
            default -> {}
        }
    }
}
