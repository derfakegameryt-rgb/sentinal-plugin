package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.util.Items;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.util.ArrayList;
import java.util.List;

public final class PlayersGui extends Gui {
    private static final int PAGE_SIZE = 45;
    private static final int PREV = 45, REPORTS = 47, STAFF = 48, VANISH = 49, CLOSE = 52, NEXT = 53;

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
            inventory.setItem(i, Items.head(p, Component.text(p.getName()), List.of(
                Component.text(plugin.punishments().activeMute(p.getUniqueId(), now) != null ? "Muted" : "Not muted"),
                Component.text("Warns: " + plugin.punishments().warnCount(p.getUniqueId())))));
        }
        if (page > 0) inventory.setItem(PREV, Items.button(Material.ARROW, Component.text("Previous"), List.of()));
        inventory.setItem(REPORTS, Items.button(Material.BOOK, Component.text("Reports"),
            List.of(Component.text("Open: " + plugin.reports().open().size()))));
        inventory.setItem(STAFF, Items.button(Material.NETHER_STAR, Component.text("Toggle staff chat"), List.of()));
        inventory.setItem(VANISH, Items.button(Material.ENDER_EYE, Component.text("Toggle vanish"), List.of()));
        inventory.setItem(CLOSE, Items.button(Material.BARRIER, Component.text("Close"), List.of()));
        if (from + PAGE_SIZE < players.size()) inventory.setItem(NEXT, Items.button(Material.ARROW, Component.text("Next"), List.of()));
        fillEmpty();
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        event.setCancelled(true);
        Player mod = (Player) event.getWhoClicked();
        int slot = event.getRawSlot();
        if (slot == PREV) { new PlayersGui(plugin, page - 1).open(mod); return; }
        if (slot == NEXT) { new PlayersGui(plugin, page + 1).open(mod); return; }
        if (slot == CLOSE) { mod.closeInventory(); return; }
        if (slot == REPORTS) { new ReportsGui(plugin, 0).open(mod); return; }
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
