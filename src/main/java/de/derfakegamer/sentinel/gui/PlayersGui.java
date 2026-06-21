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

        CompletableFuture<java.util.List<de.derfakegamer.sentinel.model.Report>> reportsFuture =
            plugin.reports().open();

        if (count <= 0) {
            // No players on this page — only wait for report count
            plugin.db().callback(reportsFuture, reports ->
                new PlayersGui(plugin, page, players, new boolean[0], new int[0],
                    reports != null ? reports.size() : 0).open(viewer));
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
            CompletableFuture.allOf(warnFutures),
            reportsFuture);

        plugin.db().callback(all, ignored -> {
            boolean[] muted = new boolean[count];
            int[] warns = new int[count];
            for (int i = 0; i < count; i++) {
                muted[i] = typedMute[i].join() != null;
                warns[i] = typedWarn[i].join();
            }
            java.util.List<de.derfakegamer.sentinel.model.Report> reports = reportsFuture.join();
            int reportCount = reports != null ? reports.size() : 0;
            new PlayersGui(plugin, page, players, muted, warns, reportCount).open(viewer);
        });
    }

    private final boolean[] muted;
    private final int[] warns;
    private final int reportCount;

    PlayersGui(Sentinel plugin, int page, List<Player> players, boolean[] muted, int[] warns, int reportCount) {
        super(plugin);
        this.page = page;
        this.players = players;
        this.muted = muted;
        this.warns = warns;
        this.reportCount = reportCount;
        this.inventory = Bukkit.createInventory(this, 54, plugin.messages().plain("gui-players-title"));

        int from = page * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE && from + i < players.size() && i < muted.length; i++) {
            Player p = players.get(from + i);
            inventory.setItem(i, Items.head(p, Component.text(p.getName(), NamedTextColor.AQUA), List.of(
                line(muted[i] ? "Muted" : "Not muted", muted[i] ? NamedTextColor.RED : NamedTextColor.GREEN),
                line("Warns: " + warns[i], NamedTextColor.GRAY))));
        }
        boolean hasNext = from + PAGE_SIZE < players.size();
        navBar(page > 0, hasNext, true);
        inventory.setItem(NAV_ACT_L1, Items.button(Material.OAK_SIGN, Component.text("Search", NamedTextColor.AQUA),
            List.of(hint("Find a player by name"))));
        inventory.setItem(NAV_ACT_L2, Items.button(Material.BOOK, Component.text("Reports", NamedTextColor.AQUA),
            List.of(hint("View open player reports"),
                    line("Open: " + reportCount, NamedTextColor.GRAY))));
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
        if (slot == NAV_PREV) { PlayersGui.open(plugin, page - 1, mod); return; }
        if (slot == NAV_NEXT) { PlayersGui.open(plugin, page + 1, mod); return; }
        if (slot == NAV_CLOSE) { mod.closeInventory(); return; }
        if (slot == NAV_BACK) { new AdminPanelGui(plugin).open(mod); return; }
        if (slot == NAV_ACT_L1) {
            mod.closeInventory();
            mod.sendMessage(plugin.messages().prefixed("enter-search"));
            plugin.chatInput().await(mod.getUniqueId(), q -> SearchResultsGui.open(plugin, q, mod));
            return;
        }
        if (slot == NAV_ACT_L2) { ReportsGui.open(plugin, 0, mod); return; }
        int index = page * PAGE_SIZE + slot;
        if (slot >= 0 && slot < PAGE_SIZE && index < players.size()) {
            PlayerActionsGui.open(plugin, players.get(index), mod);
        }
    }
}
