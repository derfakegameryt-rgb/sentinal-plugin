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
import java.util.ArrayList;
import java.util.List;

public final class HistoryGui extends Gui {
    private static final int PAGE_SIZE = 45;
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
            String dateStr = DATE.format(Instant.ofEpochMilli(p.createdAt()));
            List<Component> lore = new ArrayList<>(
                plugin.messages().list("gui.history.reason-lore",
                    "reason", p.reason(),
                    "issuer", p.issuerName(),
                    "date", dateStr));
            lore.add(p.active()
                ? plugin.messages().plain("gui.history.active")
                    .decoration(TextDecoration.ITALIC, false)
                : plugin.messages().plain("gui.history.expired")
                    .decoration(TextDecoration.ITALIC, false));
            inventory.setItem(i, Items.button(iconFor(p.type()),
                Component.text(p.type().name(), NamedTextColor.AQUA), lore));
        }
        navBar(page > 0, from + PAGE_SIZE < total, true);
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
            case Gui.NAV_PREV -> open(plugin, target, mod, page - 1);
            case Gui.NAV_NEXT -> open(plugin, target, mod, page + 1);
            case Gui.NAV_BACK -> PlayerActionsGui.open(plugin, target, mod);
            case Gui.NAV_CLOSE -> mod.closeInventory();
            default -> {}
        }
    }
}
