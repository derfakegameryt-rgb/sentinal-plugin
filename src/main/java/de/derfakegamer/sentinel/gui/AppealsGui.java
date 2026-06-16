package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.Appeal;
import de.derfakegamer.sentinel.model.PunishmentType;
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
import java.util.ArrayList;
import java.util.List;

public final class AppealsGui extends Gui {
    private static final int PAGE_SIZE = 45;
    private static final int PREV = 45, BACK = 48, CLOSE = 49, NEXT = 53;
    private static final DateTimeFormatter DATE =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC);

    private final int page;
    private final List<Appeal> appeals;

    public AppealsGui(Sentinel plugin, int page) {
        super(plugin);
        this.page = page;
        this.appeals = plugin.appeals().open();
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
        if (page > 0) inventory.setItem(PREV, Items.button(Material.ARROW, Component.text("Previous", NamedTextColor.GRAY),
            List.of(line("Go to the previous page"))));
        inventory.setItem(BACK, Items.button(Material.ARROW, Component.text("Back", NamedTextColor.GRAY),
            List.of(line("Return to the Admin Panel"))));
        inventory.setItem(CLOSE, Items.button(Material.BARRIER, Component.text("Close", NamedTextColor.RED),
            List.of(line("Close this menu"))));
        if (from + PAGE_SIZE < appeals.size()) inventory.setItem(NEXT, Items.button(Material.ARROW, Component.text("Next", NamedTextColor.GRAY),
            List.of(line("Go to the next page"))));
        fillEmpty();
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
        if (slot == PREV) { new AppealsGui(plugin, page - 1).open(p); return; }
        if (slot == NEXT) { new AppealsGui(plugin, page + 1).open(p); return; }
        if (slot == BACK) { new AdminPanelGui(plugin).open(p); return; }
        if (slot == CLOSE) { p.closeInventory(); return; }

        int index = page * PAGE_SIZE + slot;
        if (slot < 0 || slot >= PAGE_SIZE || index >= appeals.size()) return;
        Appeal a = appeals.get(index);
        long now = System.currentTimeMillis();

        if (event.isRightClick()) {
            plugin.appeals().deny(a, p.getName(), now);
            p.sendMessage(plugin.messages().prefixed("appeal-denied", "player", a.targetName()));
            new AppealsGui(plugin, page).open(p);
        } else if (event.isLeftClick()) {
            String node = a.type() == PunishmentType.MUTE ? "sentinel.unmute" : "sentinel.unban";
            if (!plugin.staffPerms().canUse(p, node)) {
                p.sendMessage(plugin.messages().prefixed("no-permission"));
                return;
            }
            plugin.appeals().accept(a, p.getName(), now);
            p.sendMessage(plugin.messages().prefixed("appeal-accepted", "player", a.targetName()));
            new AppealsGui(plugin, page).open(p);
        }
    }
}
