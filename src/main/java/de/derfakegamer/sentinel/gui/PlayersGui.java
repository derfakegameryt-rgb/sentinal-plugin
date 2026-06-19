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
import java.util.concurrent.CompletableFuture;

public final class PlayersGui extends Gui {
    private static final int PAGE_SIZE = 45;
    private static final int PREV = 45, SEARCH = 46, REPORTS = 47, STAFF = 48, VANISH = 49, PANEL = 50, CLOSE = 52, NEXT = 53;

    private final int page;
    private final List<Player> players;

    /**
     * Asynchronously fetches per-player mute/warn state, then constructs and opens the GUI on the
     * main thread. Use this instead of {@code new PlayersGui(...).open(viewer)}.
     */
    public static void open(Sentinel plugin, int page, Player viewer) {
        List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
        players.sort(java.util.Comparator.comparing(
            p -> p.getName() != null ? p.getName() : p.getUniqueId().toString(),
            String.CASE_INSENSITIVE_ORDER));

        int from = page * PAGE_SIZE;
        int count = Math.min(PAGE_SIZE, players.size() - from);

        if (count <= 0) {
            // No players on this page — open immediately
            plugin.getServer().getScheduler().runTask(plugin, () ->
                new PlayersGui(plugin, page, players, new boolean[0], new int[0]).open(viewer));
            return;
        }

        long now = System.currentTimeMillis();
        CompletableFuture<?>[] muteFutures  = new CompletableFuture[count];
        CompletableFuture<?>[] warnFutures  = new CompletableFuture[count];
        for (int i = 0; i < count; i++) {
            Player p = players.get(from + i);
            muteFutures[i] = plugin.punishments().activeMute(p.getUniqueId(), now);
            warnFutures[i] = plugin.punishments().warnCount(p.getUniqueId());
        }
        @SuppressWarnings("unchecked")
        CompletableFuture<de.derfakegamer.sentinel.model.Punishment>[] typedMute =
            (CompletableFuture<de.derfakegamer.sentinel.model.Punishment>[]) muteFutures;
        @SuppressWarnings("unchecked")
        CompletableFuture<Integer>[] typedWarn = (CompletableFuture<Integer>[]) warnFutures;

        CompletableFuture<Void> all = CompletableFuture.allOf(
            CompletableFuture.allOf(muteFutures),
            CompletableFuture.allOf(warnFutures));

        plugin.db().callback(all, ignored -> {
            boolean[] muted = new boolean[count];
            int[] warns = new int[count];
            for (int i = 0; i < count; i++) {
                muted[i] = typedMute[i].join() != null;
                warns[i] = typedWarn[i].join();
            }
            new PlayersGui(plugin, page, players, muted, warns).open(viewer);
        });
    }

    private final boolean[] muted;
    private final int[] warns;

    PlayersGui(Sentinel plugin, int page, List<Player> players, boolean[] muted, int[] warns) {
        super(plugin);
        this.page = page;
        this.players = players;
        this.muted = muted;
        this.warns = warns;
        this.inventory = Bukkit.createInventory(this, 54, plugin.messages().plain("gui-players-title"));

        int from = page * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE && from + i < players.size() && i < muted.length; i++) {
            Player p = players.get(from + i);
            inventory.setItem(i, Items.head(p, Component.text(p.getName(), NamedTextColor.AQUA), List.of(
                line(muted[i] ? "Muted" : "Not muted", muted[i] ? NamedTextColor.RED : NamedTextColor.GREEN),
                line("Warns: " + warns[i], NamedTextColor.GRAY))));
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
        if (slot == PREV) { PlayersGui.open(plugin, page - 1, mod); return; }
        if (slot == NEXT) { PlayersGui.open(plugin, page + 1, mod); return; }
        if (slot == CLOSE) { mod.closeInventory(); return; }
        if (slot == SEARCH) {
            mod.closeInventory();
            mod.sendMessage(plugin.messages().prefixed("enter-search"));
            plugin.chatInput().await(mod.getUniqueId(), q -> SearchResultsGui.open(plugin, q, mod));
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
            PlayerActionsGui.open(plugin, players.get(index), mod);
        }
    }
}
