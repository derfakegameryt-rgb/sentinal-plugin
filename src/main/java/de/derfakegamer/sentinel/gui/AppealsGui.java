package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.Appeal;
import de.derfakegamer.sentinel.model.PunishmentType;
import de.derfakegamer.sentinel.util.Items;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public final class AppealsGui extends Gui {
    private static final int PAGE_SIZE = 45;
    private static final DateTimeFormatter DATE =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC);

    private final int page;
    private final List<Appeal> appeals;

    /** Fetches the open appeals list asynchronously and opens the GUI on the main thread. */
    public static void open(Sentinel plugin, Player viewer, int page) {
        plugin.db().callback(plugin.appeals().open(), appeals -> new AppealsGui(plugin, page, appeals).open(viewer));
    }

    public AppealsGui(Sentinel plugin, int page, List<Appeal> appeals) {
        super(plugin);
        this.page = page;
        this.appeals = appeals;
        this.inventory = Bukkit.createInventory(this, 54, plugin.messages().plain("gui-appeals-title"));

        int from = page * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE && from + i < appeals.size(); i++) {
            Appeal a = appeals.get(from + i);
            OfflinePlayer target = Bukkit.getOfflinePlayer(a.targetUuid());
            List<Component> lore = new ArrayList<>();
            lore.add(line("Type: " + a.type()));
            for (String chunk : wrap(a.text(), 40)) lore.add(line("\"" + chunk + "\""));
            lore.add(line("At: " + DATE.format(Instant.ofEpochMilli(a.createdAt()))));
            lore.add(line("Left-click: Accept · Right-click: Deny"));
            inventory.setItem(i, Items.head(target, Component.text(a.targetName(), NamedTextColor.AQUA), lore));
        }
        navBar(page > 0, from + PAGE_SIZE < appeals.size(), true);
    }

    private static Component line(String text) {
        return Component.text(text, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false);
    }

    /** Splits a long string into chunks of at most {@code max} characters (on a best-effort word basis). */
    private static List<String> wrap(String s, int max) {
        List<String> out = new ArrayList<>();
        String rest = s == null ? "" : s.trim();
        if (rest.isEmpty()) { out.add(""); return out; }
        while (rest.length() > max) {
            int cut = rest.lastIndexOf(' ', max);
            if (cut <= 0) cut = max;
            out.add(rest.substring(0, cut).trim());
            rest = rest.substring(cut).trim();
        }
        out.add(rest);
        return out;
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        event.setCancelled(true);
        Player p = (Player) event.getWhoClicked();
        if (!plugin.staffPerms().canUse(p, "sentinel.use")) return;
        int slot = event.getRawSlot();
        if (slot == Gui.NAV_PREV) { AppealsGui.open(plugin, p, page - 1); return; }
        if (slot == Gui.NAV_NEXT) { AppealsGui.open(plugin, p, page + 1); return; }
        if (slot == Gui.NAV_BACK) { new AdminPanelGui(plugin).open(p); return; }
        if (slot == Gui.NAV_CLOSE) { p.closeInventory(); return; }

        int index = page * PAGE_SIZE + slot;
        if (slot < 0 || slot >= PAGE_SIZE || index >= appeals.size()) return;
        Appeal a = appeals.get(index);
        long now = System.currentTimeMillis();

        if (event.isRightClick()) {
            // deny is fire-and-forget; refresh the list after calling it
            plugin.appeals().deny(a, p.getName(), now);
            p.sendMessage(plugin.messages().prefixed("appeal-denied", "player", a.targetName()));
            AppealsGui.open(plugin, p, page);
        } else if (event.isLeftClick()) {
            String node = a.type() == PunishmentType.MUTE ? "sentinel.unmute" : "sentinel.unban";
            if (!plugin.staffPerms().canUse(p, node)) {
                p.sendMessage(plugin.messages().prefixed("no-permission"));
                return;
            }
            plugin.db().callback(plugin.appeals().accept(a, p.getName(), now), ignored -> {
                p.sendMessage(plugin.messages().prefixed("appeal-accepted", "player", a.targetName()));
                AppealsGui.open(plugin, p, page);
            });
        }
    }
}
