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

public final class OperatorsGui extends Gui {
    private static final int PAGE_SIZE = 45;
    private static final int PREV = 45, BACK = 49, NEXT = 53;

    private final int page;
    private final List<OfflinePlayer> ops;

    public OperatorsGui(Sentinel plugin, int page) {
        super(plugin);
        this.page = page;
        this.ops = new ArrayList<>(Bukkit.getOperators());
        this.ops.sort(java.util.Comparator.comparing(
            op -> op.getName() != null ? op.getName() : op.getUniqueId().toString(),
            String.CASE_INSENSITIVE_ORDER));
        this.inventory = Bukkit.createInventory(this, 54, plugin.messages().plain("gui-operators-title"));
        int from = page * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE && from + i < ops.size(); i++) {
            OfflinePlayer op = ops.get(from + i);
            String name = op.getName() != null ? op.getName() : op.getUniqueId().toString();
            inventory.setItem(i, Items.head(op, Component.text(name, NamedTextColor.AQUA),
                List.of(Component.text(op.isOnline() ? "Online" : "Offline", NamedTextColor.GRAY)
                            .decoration(TextDecoration.ITALIC, false),
                        Component.text("Click to manage", NamedTextColor.GRAY)
                            .decoration(TextDecoration.ITALIC, false))));
        }
        if (page > 0) inventory.setItem(PREV, Items.button(Material.ARROW, Component.text("Previous", NamedTextColor.GRAY), List.of()));
        inventory.setItem(BACK, Items.button(Material.BARRIER, Component.text("Back", NamedTextColor.RED), List.of()));
        if (from + PAGE_SIZE < ops.size()) inventory.setItem(NEXT, Items.button(Material.ARROW, Component.text("Next", NamedTextColor.GRAY), List.of()));
        fillEmpty();
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        event.setCancelled(true);
        Player p = (Player) event.getWhoClicked();
        int slot = event.getRawSlot();
        if (slot == PREV) { new OperatorsGui(plugin, page - 1).open(p); return; }
        if (slot == NEXT) { new OperatorsGui(plugin, page + 1).open(p); return; }
        if (slot == BACK) { new AdminPanelGui(plugin).open(p); return; }
        int index = page * PAGE_SIZE + slot;
        if (slot >= 0 && slot < PAGE_SIZE && index < ops.size())
            PlayerActionsGui.open(plugin, ops.get(index), p);
    }
}
