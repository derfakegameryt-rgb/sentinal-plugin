package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.util.Items;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

public abstract class Gui implements InventoryHolder {
    protected final Sentinel plugin;
    protected Inventory inventory;

    protected Gui(Sentinel plugin) { this.plugin = plugin; }

    @Override public @NotNull Inventory getInventory() { return inventory; }

    /** Called for every click in this GUI. Implementations MUST cancel the event first. */
    public abstract void onClick(InventoryClickEvent event);

    public void onClose(InventoryCloseEvent event) {}

    public void open(Player player) { player.openInventory(inventory); }

    /** Fills every empty slot with the blue glass filler. */
    protected void fillEmpty() {
        for (int i = 0; i < inventory.getSize(); i++) {
            if (inventory.getItem(i) == null) inventory.setItem(i, Items.filler());
        }
    }

    /** Fills the outer border (top row, bottom row, left+right columns) with gray-glass filler. */
    protected void border() {
        int size = inventory.getSize();
        int rows = size / 9;
        for (int c = 0; c < 9; c++) { set(c); set((rows - 1) * 9 + c); }       // top + bottom rows
        for (int r = 0; r < rows; r++) { set(r * 9); set(r * 9 + 8); }          // left + right columns
    }

    private void set(int slot) { if (inventory.getItem(slot) == null) inventory.setItem(slot, Items.filler()); }

    /** Standard bottom nav for paginated list GUIs (54-slot): prev (45), back (48), close (50), next (53). */
    protected void navBar(boolean hasPrev, boolean hasNext) {
        for (int i = 45; i <= 53; i++) if (inventory.getItem(i) == null) inventory.setItem(i, Items.filler());
        if (hasPrev) inventory.setItem(45, Items.button(org.bukkit.Material.ARROW,
            net.kyori.adventure.text.Component.text("Previous", net.kyori.adventure.text.format.NamedTextColor.GRAY), java.util.List.of()));
        inventory.setItem(48, Items.button(org.bukkit.Material.ARROW,
            net.kyori.adventure.text.Component.text("Back", net.kyori.adventure.text.format.NamedTextColor.GRAY), java.util.List.of()));
        inventory.setItem(50, Items.button(org.bukkit.Material.BARRIER,
            net.kyori.adventure.text.Component.text("Close", net.kyori.adventure.text.format.NamedTextColor.RED), java.util.List.of()));
        if (hasNext) inventory.setItem(53, Items.button(org.bukkit.Material.ARROW,
            net.kyori.adventure.text.Component.text("Next", net.kyori.adventure.text.format.NamedTextColor.GRAY), java.util.List.of()));
    }
}
