package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.util.Items;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.List;

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

    /** Fills the outer border (top row, bottom row, left+right columns) with the accent glass. */
    protected void border() {
        int size = inventory.getSize();
        int rows = size / 9;
        for (int c = 0; c < 9; c++) { set(c); set((rows - 1) * 9 + c); }       // top + bottom rows
        for (int r = 0; r < rows; r++) { set(r * 9); set(r * 9 + 8); }          // left + right columns
    }

    private void set(int slot) { if (inventory.getItem(slot) == null) inventory.setItem(slot, Items.accent()); }

    // ---- Standard bottom control row (row 6 of a 54-slot GUI) -------------------
    // Single layout for every list GUI: Previous (45) and Next (53) page arrows on
    // the far ends, Back (48) and Close (50) in the middle, and up to four per-GUI
    // action buttons in the gaps — two on the left (46, 47), two on the right (51, 52).
    public static final int NAV_PREV = 45, NAV_ACT_L1 = 46, NAV_ACT_L2 = 47,
                            NAV_BACK = 48, NAV_CLOSE = 50,
                            NAV_ACT_R1 = 51, NAV_ACT_R2 = 52, NAV_NEXT = 53;

    /**
     * Fills the bottom control row (45–53) with glass and places the standard controls:
     * Previous ({@link #NAV_PREV}, when {@code hasPrev}), Back ({@link #NAV_BACK}, when
     * {@code hasBack}), Close ({@link #NAV_CLOSE}), and Next ({@link #NAV_NEXT}, when
     * {@code hasNext}). Callers add their own buttons at {@link #NAV_ACT_L1}/{@link #NAV_ACT_L2}
     * and {@link #NAV_ACT_R1}/{@link #NAV_ACT_R2} (they overwrite the glass placed here).
     * <p>As its final step this also glass-fills every remaining empty slot (the content area on
     * partial pages), so callers do not need a separate {@code fillEmpty()} after it. Action
     * buttons set AFTER this call still overwrite the glass at their slots.
     */
    protected void navBar(boolean hasPrev, boolean hasNext, boolean hasBack) {
        for (int i = 45; i <= 53; i++) if (inventory.getItem(i) == null) inventory.setItem(i, Items.accent());
        if (hasPrev) inventory.setItem(NAV_PREV, navIcon(Material.ARROW, "Previous"));
        if (hasBack) inventory.setItem(NAV_BACK, navIcon(Material.OAK_DOOR, "Back"));
        inventory.setItem(NAV_CLOSE, Items.button(Material.BARRIER,
            Component.text("Close", NamedTextColor.RED), List.of()));
        if (hasNext) inventory.setItem(NAV_NEXT, navIcon(Material.ARROW, "Next"));
        fillEmpty();
    }

    private static ItemStack navIcon(Material material, String label) {
        return Items.button(material, Component.text(label, NamedTextColor.GRAY), List.of());
    }
}
