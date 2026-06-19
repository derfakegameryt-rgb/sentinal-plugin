package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

public final class GuiListener implements Listener {
    private final Sentinel plugin;

    public GuiListener(Sentinel plugin) { this.plugin = plugin; }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (event.getInventory().getHolder() instanceof Gui gui) {
            playClick(event.getWhoClicked());
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

    private void playClick(org.bukkit.entity.HumanEntity who) {
        if (!plugin.getConfig().getBoolean("gui.sound", true)) return;
        if (!(who instanceof Player p)) return;
        try {
            Sound sound = Sound.valueOf(plugin.getConfig().getString("gui.sound-name", "UI_BUTTON_CLICK"));
            p.playSound(p.getLocation(), sound, 0.4f, 1.0f);
        } catch (IllegalArgumentException ignored) { /* unknown sound name in config */ }
    }
}
