package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.PlayerRecord;
import de.derfakegamer.sentinel.util.Items;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class SearchResultsGui extends Gui {
    private static final int PAGE_SIZE = 45;

    private final List<OfflinePlayer> results = new ArrayList<>();

    /**
     * Asynchronously fetches stored player record for {@code query} then constructs and opens the
     * GUI on the main thread. Use this instead of {@code new SearchResultsGui(...).open(viewer)}.
     */
    public static void open(Sentinel plugin, String query, Player viewer) {
        plugin.db().callback(plugin.players().byName(query),
            stored -> new SearchResultsGui(plugin, query, stored).open(viewer));
    }

    /**
     * Constructs the GUI with pre-fetched stored player record. Call
     * {@link #open(Sentinel, String, Player)} from the main thread instead of this constructor.
     */
    public SearchResultsGui(Sentinel plugin, String query, PlayerRecord stored) {
        super(plugin);
        this.inventory = Bukkit.createInventory(this, 54, plugin.messages().plain("gui-search-title"));
        String low = query.toLowerCase();

        Map<UUID, OfflinePlayer> found = new LinkedHashMap<>();
        for (Player p : Bukkit.getOnlinePlayers())
            if (p.getName().toLowerCase().contains(low)) found.put(p.getUniqueId(), p);
        if (stored != null) found.putIfAbsent(stored.uuid(), Bukkit.getOfflinePlayer(stored.uuid()));

        results.addAll(found.values());
        results.sort(java.util.Comparator.comparing(
            op -> op.getName() != null ? op.getName() : op.getUniqueId().toString(),
            String.CASE_INSENSITIVE_ORDER));
        for (int i = 0; i < PAGE_SIZE && i < results.size(); i++) {
            OfflinePlayer op = results.get(i);
            String name = op.getName() != null ? op.getName() : query;
            inventory.setItem(i, Items.head(op, Component.text(name, NamedTextColor.AQUA),
                List.of(Component.text("Click to open actions", NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false))));
        }
        navBar(false, false, true);
        fillEmpty();
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        event.setCancelled(true);
        Player mod = (Player) event.getWhoClicked();
        int slot = event.getRawSlot();
        if (slot == Gui.NAV_BACK) { PlayersGui.open(plugin, 0, mod); return; }
        if (slot == Gui.NAV_CLOSE) { mod.closeInventory(); return; }
        if (slot >= 0 && slot < PAGE_SIZE && slot < results.size())
            PlayerActionsGui.open(plugin, results.get(slot), mod);
    }
}
