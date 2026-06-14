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
}
