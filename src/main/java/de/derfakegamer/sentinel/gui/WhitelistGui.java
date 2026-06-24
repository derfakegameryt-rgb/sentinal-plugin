package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.util.Items;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.util.ArrayList;
import java.util.List;

public final class WhitelistGui extends Gui {
    private static final int PAGE_SIZE = 45;

    private final int page;
    private final List<OfflinePlayer> list;

    public WhitelistGui(Sentinel plugin, int page) {
        super(plugin);
        this.page = page;
        this.list = new ArrayList<>(Bukkit.getWhitelistedPlayers());
        this.list.sort(java.util.Comparator.comparing(
            op -> op.getName() != null ? op.getName() : op.getUniqueId().toString(),
            String.CASE_INSENSITIVE_ORDER));
        this.inventory = Bukkit.createInventory(this, 54, plugin.messages().plain("gui-whitelist-title"));

        int from = page * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE && from + i < list.size(); i++) {
            OfflinePlayer op = list.get(from + i);
            String name = op.getName() != null ? op.getName() : op.getUniqueId().toString();
            inventory.setItem(i, Items.head(op, Component.text(name, NamedTextColor.AQUA),
                plugin.messages().list("gui.whitelist.entry-lore")));
        }

        boolean hasNext = from + PAGE_SIZE < list.size();
        navBar(page > 0, hasNext, true);
        inventory.setItem(NAV_ACT_L1, Items.button(Material.LIME_DYE,
            plugin.messages().plain("gui.whitelist.add"),
            plugin.messages().list("gui.whitelist.add-lore")));
        boolean on = Bukkit.hasWhitelist();
        inventory.setItem(NAV_ACT_L2, Items.button(Material.LEVER,
            plugin.messages().plain(on ? "gui.whitelist.toggle-on" : "gui.whitelist.toggle-off"),
            plugin.messages().list("gui.whitelist.toggle-lore")));
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        event.setCancelled(true);
        Player p = (Player) event.getWhoClicked();
        int slot = event.getRawSlot();
        if (slot == NAV_PREV) { new WhitelistGui(plugin, page - 1).open(p); return; }
        if (slot == NAV_NEXT) { new WhitelistGui(plugin, page + 1).open(p); return; }
        if (slot == NAV_BACK) { new AdminPanelGui(plugin).open(p); return; }
        if (slot == NAV_CLOSE) { p.closeInventory(); return; }
        if (slot == NAV_ACT_L2) {
            if (!plugin.staffPerms().canUse(p, "sentinel.use")) { p.sendMessage(plugin.messages().prefixed("no-permission")); return; }
            Bukkit.setWhitelist(!Bukkit.hasWhitelist());
            p.sendMessage(plugin.messages().prefixed(Bukkit.hasWhitelist() ? "whitelist-on" : "whitelist-off"));
            new WhitelistGui(plugin, page).open(p);
            return;
        }
        if (slot == NAV_ACT_L1) {
            if (!plugin.staffPerms().canUse(p, "sentinel.use")) { p.sendMessage(plugin.messages().prefixed("no-permission")); return; }
            p.closeInventory();
            p.sendMessage(plugin.messages().prefixed("whitelist-enter"));
            plugin.chatInput().await(p.getUniqueId(), name -> {
                if (name.equalsIgnoreCase("cancel")) return;
                plugin.scheduler().runGlobal(() -> {
                    OfflinePlayer t = Bukkit.getOfflinePlayer(name);
                    t.setWhitelisted(true);
                    p.sendMessage(plugin.messages().prefixed("whitelist-added", "player", name));
                    new WhitelistGui(plugin, 0).open(p);
                });
            });
            return;
        }
        int index = page * PAGE_SIZE + slot;
        if (slot >= 0 && slot < PAGE_SIZE && index < list.size()) {
            if (!plugin.staffPerms().canUse(p, "sentinel.use")) { p.sendMessage(plugin.messages().prefixed("no-permission")); return; }
            OfflinePlayer t = list.get(index);
            plugin.scheduler().runGlobal(() -> t.setWhitelisted(false));
            p.sendMessage(plugin.messages().prefixed("whitelist-removed", "player",
                t.getName() != null ? t.getName() : t.getUniqueId().toString()));
            new WhitelistGui(plugin, page).open(p);
        }
    }
}
