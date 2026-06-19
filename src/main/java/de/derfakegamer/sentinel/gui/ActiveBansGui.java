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
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.util.List;

public final class ActiveBansGui extends Gui {
    private static final int PAGE_SIZE = 45;
    private static final int PREV = 45, BACK = 49, NEXT = 53;

    private final int page;
    private final List<Punishment> bans;

    /** Async opener: fetches active bans then constructs and opens the GUI on the main thread. */
    public static void open(Sentinel plugin, Player viewer, int page) {
        plugin.db().callback(plugin.punishments().activeList(PunishmentType.BAN, System.currentTimeMillis()),
            bans -> new ActiveBansGui(plugin, bans != null ? bans : List.of(), page).open(viewer));
    }

    public ActiveBansGui(Sentinel plugin, List<Punishment> bans, int page) {
        super(plugin);
        this.page = page;
        this.bans = bans;
        this.inventory = Bukkit.createInventory(this, 54, plugin.messages().plain("gui-bans-title"));
        int from = page * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE && from + i < bans.size(); i++) {
            Punishment b = bans.get(from + i);
            inventory.setItem(i, Items.head(Bukkit.getOfflinePlayer(b.targetUuid()),
                Component.text(b.targetName(), NamedTextColor.AQUA),
                List.of(grey("Reason: " + b.reason()),
                        grey("By: " + b.issuerName()),
                        grey(b.isPermanent() ? "Permanent" : "Temporary"),
                        grey("Click to manage / unban"))));
        }
        if (page > 0) inventory.setItem(PREV, Items.button(Material.ARROW, Component.text("Previous", NamedTextColor.GRAY), List.of()));
        inventory.setItem(BACK, Items.button(Material.BARRIER, Component.text("Back", NamedTextColor.RED), List.of()));
        if (from + PAGE_SIZE < bans.size()) inventory.setItem(NEXT, Items.button(Material.ARROW, Component.text("Next", NamedTextColor.GRAY), List.of()));
        fillEmpty();
    }

    private Component grey(String s) { return Component.text(s, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false); }

    @Override
    public void onClick(InventoryClickEvent event) {
        event.setCancelled(true);
        Player p = (Player) event.getWhoClicked();
        int slot = event.getRawSlot();
        if (slot == PREV) { open(plugin, p, page - 1); return; }
        if (slot == NEXT) { open(plugin, p, page + 1); return; }
        if (slot == BACK) { new AdminPanelGui(plugin).open(p); return; }
        int index = page * PAGE_SIZE + slot;
        if (slot >= 0 && slot < PAGE_SIZE && index < bans.size())
            PlayerActionsGui.open(plugin, Bukkit.getOfflinePlayer(bans.get(index).targetUuid()), p);
    }
}
