package de.derfakegamer.sentinel.gui;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

public final class GuiListener implements Listener {
    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (event.getInventory().getHolder() instanceof Gui gui) {
            gui.onClick(event);
        }
    }

    /** Drags are a separate event from clicks; cancel any drag touching a Sentinel GUI's slots. */
    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Gui) {
            int topSize = event.getInventory().getSize();
            if (event.getRawSlots().stream().anyMatch(slot -> slot < topSize)) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof Gui gui) {
            gui.onClose(event);
        }
    }
}
