package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.Report;
import de.derfakegamer.sentinel.util.Items;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
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
    private static final int PREV = 45, CLOSE = 49, NEXT = 53;
    private static final DateTimeFormatter DATE =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC);

    private final int page;
    private final List<Report> reports;

    public ReportsGui(Sentinel plugin, int page) {
        super(plugin);
        this.page = page;
        this.reports = plugin.reports().open();
        this.inventory = Bukkit.createInventory(this, 54, plugin.messages().plain("gui-reports-title"));

        int from = page * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE && from + i < reports.size(); i++) {
            Report r = reports.get(from + i);
            inventory.setItem(i, Items.button(Material.PAPER, Component.text(r.targetName(), NamedTextColor.AQUA), List.of(
                line("Reported by: " + r.reporterName()),
                line("Reason: " + r.reason()),
                line("At: " + DATE.format(Instant.ofEpochMilli(r.createdAt()))),
                line("Left: teleport  Right: actions  Shift: handled"))));
        }
        if (page > 0) inventory.setItem(PREV, Items.button(Material.ARROW, Component.text("Previous", NamedTextColor.GRAY),
            List.of(line("Go to the previous page"))));
        inventory.setItem(CLOSE, Items.button(Material.BARRIER, Component.text("Close", NamedTextColor.RED),
            List.of(line("Close this menu"))));
        if (from + PAGE_SIZE < reports.size()) inventory.setItem(NEXT, Items.button(Material.ARROW, Component.text("Next", NamedTextColor.GRAY),
            List.of(line("Go to the next page"))));
        fillEmpty();
    }

    private static Component line(String text) {
        return Component.text(text, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false);
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        event.setCancelled(true);
        Player mod = (Player) event.getWhoClicked();
        int slot = event.getRawSlot();
        if (slot == PREV) { new ReportsGui(plugin, page - 1).open(mod); return; }
        if (slot == NEXT) { new ReportsGui(plugin, page + 1).open(mod); return; }
        if (slot == CLOSE) { mod.closeInventory(); return; }

        int index = page * PAGE_SIZE + slot;
        if (slot < 0 || slot >= PAGE_SIZE || index >= reports.size()) return;
        Report r = reports.get(index);

        if (event.isShiftClick()) {
            plugin.reports().handle(r.id(), mod.getName());
            mod.sendMessage(plugin.messages().prefixed("report-handled"));
            new ReportsGui(plugin, page).open(mod);
        } else if (event.isRightClick()) {
            OfflinePlayer target = Bukkit.getOfflinePlayer(r.targetUuid());
            PlayerActionsGui.open(plugin, target, mod);
        } else {
            Player target = Bukkit.getPlayer(r.targetUuid());
            if (target != null) { mod.teleport(target.getLocation()); mod.closeInventory(); }
        }
    }
}
