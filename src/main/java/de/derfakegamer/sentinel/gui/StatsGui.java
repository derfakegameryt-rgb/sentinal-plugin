package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.PlayerRecord;
import de.derfakegamer.sentinel.util.Items;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.util.List;

public final class StatsGui extends Gui {
    private static final int BACK = 45, CLOSE = 53;

    public StatsGui(Sentinel plugin) {
        super(plugin);
        this.inventory = Bukkit.createInventory(this, 54, plugin.messages().plain("gui-stats-title"));
        List<PlayerRecord> top = plugin.players().topByPlaytime(45);
        for (int i = 0; i < top.size() && i < 45; i++) {
            PlayerRecord r = top.get(i);
            long ms = plugin.players().playtime(r.uuid());
            inventory.setItem(i, Items.head(Bukkit.getOfflinePlayer(r.uuid()),
                Component.text("#" + (i + 1) + " " + r.name(), NamedTextColor.AQUA),
                List.of(Component.text("Playtime: " + (ms / 3600000) + "h " + (ms / 60000 % 60) + "m", NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false))));
        }
        inventory.setItem(BACK, Items.button(Material.ARROW, Component.text("Back", NamedTextColor.GRAY), List.of()));
        inventory.setItem(CLOSE, Items.button(Material.BARRIER, Component.text("Close", NamedTextColor.RED), List.of()));
        fillEmpty();
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        event.setCancelled(true);
        Player p = (Player) event.getWhoClicked();
        if (event.getRawSlot() == BACK) new AdminPanelGui(plugin).open(p);
        else if (event.getRawSlot() == CLOSE) p.closeInventory();
    }
}
