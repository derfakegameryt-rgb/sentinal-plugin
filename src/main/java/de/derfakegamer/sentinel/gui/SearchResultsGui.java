package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.PlayerRecord;
import de.derfakegamer.sentinel.util.Items;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
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
    private static final int BACK = 45, CLOSE = 53;

    private final List<OfflinePlayer> results = new ArrayList<>();

    public SearchResultsGui(Sentinel plugin, String query) {
        super(plugin);
        this.inventory = Bukkit.createInventory(this, 54, plugin.messages().plain("gui-search-title"));
        String low = query.toLowerCase();

        Map<UUID, OfflinePlayer> found = new LinkedHashMap<>();
        for (Player p : Bukkit.getOnlinePlayers())
            if (p.getName().toLowerCase().contains(low)) found.put(p.getUniqueId(), p);
        PlayerRecord stored = plugin.players().byName(query);
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
        inventory.setItem(BACK, Items.button(Material.ARROW, Component.text("Back", NamedTextColor.GRAY), List.of()));
        inventory.setItem(CLOSE, Items.button(Material.BARRIER, Component.text("Close", NamedTextColor.RED), List.of()));
        fillEmpty();
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        event.setCancelled(true);
        Player mod = (Player) event.getWhoClicked();
        int slot = event.getRawSlot();
        if (slot == BACK) { new PlayersGui(plugin, 0).open(mod); return; }
        if (slot == CLOSE) { mod.closeInventory(); return; }
        if (slot >= 0 && slot < PAGE_SIZE && slot < results.size())
            new PlayerActionsGui(plugin, results.get(slot)).open(mod);
    }
}
