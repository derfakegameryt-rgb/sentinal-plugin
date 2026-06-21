package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.PlayerRecord;
import de.derfakegamer.sentinel.util.Items;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.util.List;

public final class StatsGui extends Gui {

    private final List<PlayerRecord> top;

    /**
     * Asynchronously fetches playtime leaderboard then constructs and opens the GUI on the main thread.
     * Use this instead of {@code new StatsGui(...).open(viewer)}.
     */
    public static void open(Sentinel plugin, Player viewer) {
        plugin.db().callbackOrError(viewer, plugin.players().topByPlaytime(45),
            top -> new StatsGui(plugin, top).open(viewer));
    }

    /**
     * Constructs the GUI with pre-fetched leaderboard data. Call {@link #open(Sentinel, Player)}
     * from the main thread instead of this constructor.
     */
    public StatsGui(Sentinel plugin, List<PlayerRecord> top) {
        super(plugin);
        this.top = top;
        this.inventory = Bukkit.createInventory(this, 54, plugin.messages().plain("gui-stats-title"));
        for (int i = 0; i < top.size() && i < 45; i++) {
            PlayerRecord r = top.get(i);
            long ms = r.playtime();
            inventory.setItem(i, Items.head(Bukkit.getOfflinePlayer(r.uuid()),
                Component.text("#" + (i + 1) + " " + r.name(), NamedTextColor.AQUA),
                List.of(Component.text("Playtime: " + (ms / 3600000) + "h " + (ms / 60000 % 60) + "m", NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false))));
        }
        navBar(false, false, true);
        fillEmpty();
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        event.setCancelled(true);
        Player p = (Player) event.getWhoClicked();
        if (event.getRawSlot() == Gui.NAV_BACK) new AdminPanelGui(plugin).open(p);
        else if (event.getRawSlot() == Gui.NAV_CLOSE) p.closeInventory();
    }
}
