package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.ActionCount;
import de.derfakegamer.sentinel.model.ActorCount;
import de.derfakegamer.sentinel.util.Items;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class ModStatsGui extends Gui {
    private static final int BACK = 45, CLOSE = 53;
    private static final long WINDOW_MS = 30L * 24 * 3600 * 1000;
    private final List<ActorCount> actors;
    private final List<ActionCount> actions;

    public static void open(Sentinel plugin, Player viewer) {
        long since = System.currentTimeMillis() - WINDOW_MS;
        CompletableFuture<List<ActorCount>> topFuture = plugin.audit().topActors(since, 10);
        CompletableFuture<List<ActionCount>> byActFuture = plugin.audit().countsByAction(since);
        plugin.db().callback(CompletableFuture.allOf(topFuture, byActFuture), ignored -> {
            List<ActorCount> top = topFuture.getNow(List.of());
            List<ActionCount> byAct = byActFuture.getNow(List.of());
            new ModStatsGui(plugin, top, byAct).open(viewer);
        });
    }

    public ModStatsGui(Sentinel plugin, List<ActorCount> actors, List<ActionCount> actions) {
        super(plugin);
        this.actors = actors;
        this.actions = actions;
        this.inventory = Bukkit.createInventory(this, 54, plugin.messages().plain("gui-modstats-title"));
        for (int i = 0; i < actors.size() && i < 9; i++) {
            ActorCount a = actors.get(i);
            inventory.setItem(i, Items.button(Material.PLAYER_HEAD,
                Component.text("#" + (i + 1) + " " + a.actor(), NamedTextColor.AQUA),
                List.of(line(a.count() + " actions (30d)"))));
        }
        for (int i = 0; i < actions.size() && i < 9; i++) {
            ActionCount c = actions.get(i);
            inventory.setItem(18 + i, Items.button(Material.BOOK,
                Component.text(c.action(), NamedTextColor.AQUA), List.of(line(c.count() + " (30d)"))));
        }
        inventory.setItem(BACK, Items.button(Material.COMPARATOR, Component.text("Back", NamedTextColor.GRAY), List.of()));
        inventory.setItem(CLOSE, Items.button(Material.BARRIER, Component.text("Close", NamedTextColor.RED), List.of()));
        fillEmpty();
    }

    private static Component line(String s) {
        return Component.text(s, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false);
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        event.setCancelled(true);
        Player p = (Player) event.getWhoClicked();
        if (event.getRawSlot() == BACK) new AdminPanelGui(plugin).open(p);
        else if (event.getRawSlot() == CLOSE) p.closeInventory();
    }
}
