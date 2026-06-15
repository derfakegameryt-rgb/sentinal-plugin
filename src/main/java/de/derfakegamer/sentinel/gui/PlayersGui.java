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

import java.util.ArrayList;
import java.util.List;

public final class PlayersGui extends Gui {
    private static final int PAGE_SIZE = 45;
    private static final int PREV = 45, SEARCH = 46, REPORTS = 47, STAFF = 48, VANISH = 49, PANEL = 50, CLOSE = 52, NEXT = 53;

    private final int page;
    private final List<Player> players;

    public PlayersGui(Sentinel plugin, int page) {
        super(plugin);
        this.page = page;
        this.players = new ArrayList<>(Bukkit.getOnlinePlayers());
        this.inventory = Bukkit.createInventory(this, 54, plugin.messages().plain("gui-players-title"));

        int from = page * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE && from + i < players.size(); i++) {
            Player p = players.get(from + i);
            long now = System.currentTimeMillis();
            boolean muted = plugin.punishments().activeMute(p.getUniqueId(), now) != null;
            inventory.setItem(i, Items.head(p, Component.text(p.getName(), NamedTextColor.AQUA), List.of(
                line(muted ? "Muted" : "Not muted", muted ? NamedTextColor.RED : NamedTextColor.GREEN),
                line("Warns: " + plugin.punishments().warnCount(p.getUniqueId()), NamedTextColor.GRAY))));
        }
        if (page > 0) inventory.setItem(PREV, Items.button(Material.ARROW, Component.text("Previous", NamedTextColor.GRAY),
            List.of(hint("Go to the previous page"))));
        inventory.setItem(SEARCH, Items.button(Material.OAK_SIGN, Component.text("Search", NamedTextColor.AQUA),
            List.of(hint("Find a player by name"))));
        inventory.setItem(REPORTS, Items.button(Material.BOOK, Component.text("Reports", NamedTextColor.AQUA),
            List.of(hint("View open player reports"),
                    line("Open: " + plugin.reports().open().size(), NamedTextColor.GRAY))));
        inventory.setItem(STAFF, Items.button(Material.NETHER_STAR, Component.text("Toggle staff chat", NamedTextColor.LIGHT_PURPLE),
            List.of(hint("Toggle your staff-only chat"))));
        inventory.setItem(VANISH, Items.button(Material.ENDER_EYE, Component.text("Toggle vanish", NamedTextColor.AQUA),
            List.of(hint("Toggle your own vanish"))));
        inventory.setItem(PANEL, Items.button(Material.COMPARATOR,
            Component.text("Admin Panel", NamedTextColor.AQUA),
            List.of(Component.text("Server info, ops, bans, mutes, reports", NamedTextColor.GRAY)
                .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false))));
        inventory.setItem(CLOSE, Items.button(Material.BARRIER, Component.text("Close", NamedTextColor.RED),
            List.of(hint("Close this menu"))));
        if (from + PAGE_SIZE < players.size()) inventory.setItem(NEXT, Items.button(Material.ARROW, Component.text("Next", NamedTextColor.GRAY),
            List.of(hint("Go to the next page"))));
        fillEmpty();
    }

    private static Component hint(String text) {
        return Component.text(text, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false);
    }

    private static Component line(String text, NamedTextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        event.setCancelled(true);
        Player mod = (Player) event.getWhoClicked();
        int slot = event.getRawSlot();
        if (slot == PREV) { new PlayersGui(plugin, page - 1).open(mod); return; }
        if (slot == NEXT) { new PlayersGui(plugin, page + 1).open(mod); return; }
        if (slot == CLOSE) { mod.closeInventory(); return; }
        if (slot == SEARCH) {
            mod.closeInventory();
            mod.sendMessage(plugin.messages().prefixed("enter-search"));
            plugin.chatInput().await(mod.getUniqueId(), q -> new SearchResultsGui(plugin, q).open(mod));
            return;
        }
        if (slot == REPORTS) { new ReportsGui(plugin, 0).open(mod); return; }
        if (slot == PANEL) { new AdminPanelGui(plugin).open(mod); return; }
        if (slot == STAFF) {
            boolean on = plugin.staffChat().toggle(mod.getUniqueId());
            mod.sendMessage(plugin.messages().prefixed(on ? "staffchat-on" : "staffchat-off"));
            return;
        }
        if (slot == VANISH) {
            boolean vanished = plugin.vanish().toggle(mod);
            mod.sendMessage(plugin.messages().prefixed(vanished ? "vanish-on" : "vanish-off"));
            return;
        }
        int index = page * PAGE_SIZE + slot;
        if (slot >= 0 && slot < PAGE_SIZE && index < players.size()) {
            new PlayerActionsGui(plugin, players.get(index)).open(mod);
        }
    }
}
