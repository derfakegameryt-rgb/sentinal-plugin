package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.util.Items;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.util.List;

public final class ConfirmGui extends Gui {
    private static final int CONFIRM = 11, SUMMARY = 13, CANCEL = 15;

    private final Runnable onConfirm;
    private final Gui back;

    public ConfirmGui(Sentinel plugin, Component summary, Runnable onConfirm, Gui back) {
        super(plugin);
        this.onConfirm = onConfirm;
        this.back = back;
        this.inventory = Bukkit.createInventory(this, 27,
            plugin.messages().plain("gui-confirm-title"));
        inventory.setItem(CONFIRM, Items.button(Material.LIME_WOOL,
            plugin.messages().plain("gui.confirm.confirm"),
            plugin.messages().list("gui.confirm.confirm-lore")));
        inventory.setItem(SUMMARY, Items.button(Material.PAPER, summary, List.of()));
        inventory.setItem(CANCEL, Items.button(Material.RED_WOOL,
            plugin.messages().plain("gui.confirm.cancel"),
            plugin.messages().list("gui.confirm.cancel-lore")));
        border();
        fillEmpty();
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        event.setCancelled(true);
        Player player = (Player) event.getWhoClicked();
        if (event.getRawSlot() == CONFIRM) {
            player.closeInventory();
            onConfirm.run();
        } else if (event.getRawSlot() == CANCEL) {
            if (back != null) back.open(player); else player.closeInventory();
        }
    }
}
