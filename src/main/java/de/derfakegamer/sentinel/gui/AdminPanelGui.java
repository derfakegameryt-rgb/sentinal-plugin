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
    private static final int PLAYERS = 19, VANISH = 20, STAFFCHAT = 21, APPEALS = 22;
    private static final int CLOSE = 25;
    // slots 23, 24 reserved for future Audit / Stats entries

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
        inventory.setItem(APPEALS, button(Material.WRITABLE_BOOK, "Appeals", "Review ban/mute appeals"));
        inventory.setItem(PLAYERS, button(Material.PLAYER_HEAD, "Player Manager", "Browse and manage players"));
        inventory.setItem(VANISH, button(Material.ENDER_EYE, "Vanish", "Toggle your own vanish"));
        inventory.setItem(STAFFCHAT, button(Material.NETHER_STAR, "Staff Chat", "Toggle your staff-only chat"));
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
            case BANS -> ActiveBansGui.open(plugin, p, 0);
            case MUTES -> ActiveMutesGui.open(plugin, p, 0);
            case REPORTS -> ReportsGui.open(plugin, 0, p);
            case WHITELIST -> new WhitelistGui(plugin, 0).open(p);
            case STATS -> StatsGui.open(plugin, p);
            case APPEALS -> AppealsGui.open(plugin, p, 0);
            case PLAYERS -> PlayersGui.open(plugin, 0, p);
            case VANISH -> {
                boolean v = plugin.vanish().toggle(p);
                p.sendMessage(plugin.messages().prefixed(v ? "vanish-on" : "vanish-off"));
                plugin.audit().record(p.getName(), "VANISH", p.getName(), v ? "on" : "off");
            }
            case STAFFCHAT -> {
                boolean on = plugin.staffChat().toggle(p.getUniqueId());
                p.sendMessage(plugin.messages().prefixed(on ? "staffchat-on" : "staffchat-off"));
                plugin.audit().record(p.getName(), "STAFFCHAT", p.getName(), on ? "on" : "off");
            }
            case CLOSE -> p.closeInventory();
        }
    }
}
