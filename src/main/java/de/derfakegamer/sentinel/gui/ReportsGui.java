package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.Report;
import de.derfakegamer.sentinel.util.Items;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

public final class ReportsGui extends Gui {
    private static final int PAGE_SIZE = 45;
    private static final DateTimeFormatter DATE =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC);

    private final int page;
    private final List<Report> reports;

    /** Async opener: fetches open reports then constructs and opens the GUI on the main thread. */
    public static void open(Sentinel plugin, int page, Player viewer) {
        plugin.db().callback(plugin.reports().open(),
            reports -> new ReportsGui(plugin, page, reports != null ? reports : List.of()).open(viewer));
    }

    public ReportsGui(Sentinel plugin, int page, List<Report> reports) {
        super(plugin);
        this.page = page;
        this.reports = reports;
        this.inventory = Bukkit.createInventory(this, 54, plugin.messages().plain("gui-reports-title"));

        int from = page * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE && from + i < reports.size(); i++) {
            Report r = reports.get(from + i);
            String dateStr = DATE.format(Instant.ofEpochMilli(r.createdAt()));
            inventory.setItem(i, Items.button(Material.PAPER,
                Component.text(r.targetName(), NamedTextColor.AQUA),
                plugin.messages().list("gui.reports.entry-lore",
                    "reporter", r.reporterName(),
                    "reason", r.reason(),
                    "date", dateStr)));
        }
        navBar(page > 0, from + PAGE_SIZE < reports.size(), true);
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        event.setCancelled(true);
        Player mod = (Player) event.getWhoClicked();
        int slot = event.getRawSlot();
        if (slot == Gui.NAV_PREV) { ReportsGui.open(plugin, page - 1, mod); return; }
        if (slot == Gui.NAV_NEXT) { ReportsGui.open(plugin, page + 1, mod); return; }
        if (slot == Gui.NAV_BACK) { new AdminPanelGui(plugin).open(mod); return; }
        if (slot == Gui.NAV_CLOSE) { mod.closeInventory(); return; }

        int index = page * PAGE_SIZE + slot;
        if (slot < 0 || slot >= PAGE_SIZE || index >= reports.size()) return;
        Report r = reports.get(index);

        if (event.isShiftClick()) {
            plugin.reports().handle(r.id(), mod.getName());
            mod.sendMessage(plugin.messages().prefixed("report-handled"));
            ReportsGui.open(plugin, page, mod);
        } else if (event.isRightClick()) {
            OfflinePlayer target = Bukkit.getOfflinePlayer(r.targetUuid());
            PlayerActionsGui.open(plugin, target, mod);
        } else {
            Player target = Bukkit.getPlayer(r.targetUuid());
            if (target != null) { mod.teleport(target.getLocation()); mod.closeInventory(); }
        }
    }
}
