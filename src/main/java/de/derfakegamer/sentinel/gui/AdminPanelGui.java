package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.util.Items;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.util.List;

public final class AdminPanelGui extends Gui {
    private static final int INFO = 10, OPS = 11, BANS = 12, MUTES = 13, REPORTS = 14, WHITELIST = 15, STATS = 16;
    private static final int BACK = 19, CLOSE = 25;

    public AdminPanelGui(Sentinel plugin) {
        super(plugin);
        this.inventory = Bukkit.createInventory(this, 27, plugin.messages().plain("gui-panel-title"));
        inventory.setItem(INFO, button(Material.COMPARATOR, "Server Info", "Specs, TPS, memory, uptime"));
        inventory.setItem(OPS, button(Material.PLAYER_HEAD, "Operators", "Everyone with OP"));
        inventory.setItem(BANS, button(Material.IRON_BARS, "Active Bans", "Currently banned players"));
        inventory.setItem(MUTES, button(Material.BOOK, "Active Mutes", "Currently muted players"));
        inventory.setItem(REPORTS, button(Material.PAPER, "Open Reports", "Reports waiting for staff"));
        inventory.setItem(WHITELIST, button(Material.NAME_TAG, "Whitelist", "Manage the server whitelist"));
        inventory.setItem(STATS, button(Material.CLOCK, "Playtime", "Top players by playtime"));
        inventory.setItem(BACK, Items.button(Material.ARROW, Component.text("Back", NamedTextColor.GRAY), List.of()));
        inventory.setItem(CLOSE, Items.button(Material.BARRIER, Component.text("Close", NamedTextColor.RED), List.of()));
        border();
        fillEmpty();
    }

    private org.bukkit.inventory.ItemStack button(Material m, String title, String hint) {
        return Items.button(m, Component.text(title, NamedTextColor.AQUA),
            List.of(Component.text(hint, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        event.setCancelled(true);
        Player p = (Player) event.getWhoClicked();
        switch (event.getRawSlot()) {
            case INFO -> new ServerInfoGui(plugin).open(p);
            case OPS -> new OperatorsGui(plugin, 0).open(p);
            case BANS -> new ActiveBansGui(plugin, 0).open(p);
            case MUTES -> new ActiveMutesGui(plugin, 0).open(p);
            case REPORTS -> new ReportsGui(plugin, 0).open(p);
            case WHITELIST -> new WhitelistGui(plugin, 0).open(p);
            case STATS -> new StatsGui(plugin).open(p);
            case BACK -> new PlayersGui(plugin, 0).open(p);
            case CLOSE -> p.closeInventory();
        }
    }
}
