package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.Punishment;
import de.derfakegamer.sentinel.model.PunishmentType;
import de.derfakegamer.sentinel.util.Items;
import net.kyori.adventure.text.Component;
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

    public HistoryGui(Sentinel plugin, OfflinePlayer target, int page) {
        super(plugin);
        this.target = target;
        this.page = page;
        List<Punishment> all = plugin.punishments().history(target.getUniqueId());
        this.total = all.size();
        this.inventory = Bukkit.createInventory(this, 54,
            plugin.messages().plain("gui-history-title", "player", name()));

        int from = page * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE && from + i < all.size(); i++) {
            Punishment p = all.get(from + i);
            inventory.setItem(i, Items.button(iconFor(p.type()), Component.text(p.type().name()), List.of(
                Component.text("Reason: " + p.reason()),
                Component.text("By: " + p.issuerName()),
                Component.text("Date: " + DATE.format(Instant.ofEpochMilli(p.createdAt()))),
                Component.text(p.active() ? "Active" : "Removed/expired"))));
        }
        if (page > 0) inventory.setItem(PREV, Items.button(Material.ARROW, Component.text("Previous"), List.of()));
        inventory.setItem(BACK, Items.button(Material.BARRIER, Component.text("Back"), List.of()));
        if (from + PAGE_SIZE < total) inventory.setItem(NEXT, Items.button(Material.ARROW, Component.text("Next"), List.of()));
        fillEmpty();
    }

    private String name() { return target.getName() == null ? "?" : target.getName(); }

    private Material iconFor(PunishmentType type) {
        return switch (type) {
            case BAN, IPBAN -> Material.RED_WOOL;
            case MUTE       -> Material.BOOK;
            case WARN       -> Material.YELLOW_BANNER;
            case KICK       -> Material.LEATHER_BOOTS;
        };
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        event.setCancelled(true);
        Player mod = (Player) event.getWhoClicked();
        switch (event.getRawSlot()) {
            case PREV -> new HistoryGui(plugin, target, page - 1).open(mod);
            case NEXT -> new HistoryGui(plugin, target, page + 1).open(mod);
            case BACK -> new PlayerActionsGui(plugin, target).open(mod);
            default -> {}
        }
    }
}
