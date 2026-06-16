package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
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
import java.util.List;

public final class WhitelistGui extends Gui {
    private static final int PAGE_SIZE = 45;
    private static final int PREV = 45, ADD = 47, TOGGLE = 49, BACK = 50, NEXT = 53;

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
                List.of(Component.text("Click to remove from whitelist", NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false))));
        }

        if (page > 0) inventory.setItem(PREV, Items.button(Material.ARROW,
            Component.text("Previous", NamedTextColor.GRAY), List.of()));
        inventory.setItem(ADD, Items.button(Material.LIME_DYE,
            Component.text("Add Player", NamedTextColor.GREEN),
            List.of(Component.text("Type a name in chat", NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false))));
        boolean on = Bukkit.hasWhitelist();
        inventory.setItem(TOGGLE, Items.button(Material.LEVER,
            Component.text(on ? "Whitelist: ON" : "Whitelist: OFF", on ? NamedTextColor.GREEN : NamedTextColor.RED),
            List.of(Component.text("Click to toggle", NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false))));
        inventory.setItem(BACK, Items.button(Material.BARRIER,
            Component.text("Back", NamedTextColor.RED), List.of()));
        if (from + PAGE_SIZE < list.size()) inventory.setItem(NEXT, Items.button(Material.ARROW,
            Component.text("Next", NamedTextColor.GRAY), List.of()));
        fillEmpty();
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        event.setCancelled(true);
        Player p = (Player) event.getWhoClicked();
        int slot = event.getRawSlot();
        if (slot == PREV) { new WhitelistGui(plugin, page - 1).open(p); return; }
        if (slot == NEXT) { new WhitelistGui(plugin, page + 1).open(p); return; }
        if (slot == BACK) { new AdminPanelGui(plugin).open(p); return; }
        if (slot == TOGGLE) {
            Bukkit.setWhitelist(!Bukkit.hasWhitelist());
            p.sendMessage(plugin.messages().prefixed(Bukkit.hasWhitelist() ? "whitelist-on" : "whitelist-off"));
            new WhitelistGui(plugin, page).open(p);
            return;
        }
        if (slot == ADD) {
            p.closeInventory();
            p.sendMessage(plugin.messages().prefixed("whitelist-enter"));
            plugin.chatInput().await(p.getUniqueId(), name -> {
                if (name.equalsIgnoreCase("cancel")) return;
                Bukkit.getScheduler().runTask(plugin, () -> {
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
            OfflinePlayer t = list.get(index);
            t.setWhitelisted(false);
            p.sendMessage(plugin.messages().prefixed("whitelist-removed", "player",
                t.getName() != null ? t.getName() : t.getUniqueId().toString()));
            new WhitelistGui(plugin, page).open(p);
        }
    }
}
