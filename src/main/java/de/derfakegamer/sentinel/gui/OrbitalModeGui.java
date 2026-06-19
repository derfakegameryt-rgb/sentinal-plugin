package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.util.Items;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.util.List;

public final class OrbitalModeGui extends Gui {
    private static final int ROD = 10, COORDS = 12, SCHEDULED = 14, CLOSE = 26;

    public OrbitalModeGui(Sentinel plugin) {
        super(plugin);
        this.inventory = Bukkit.createInventory(this, 27, plugin.secret().plain("gui-orbital-mode-title"));
        inventory.setItem(ROD, Items.button(Material.FISHING_ROD, Component.text("Targeted (rod)", NamedTextColor.AQUA),
            List.of(hint("Pick a payload, then fire with the rod"))));
        inventory.setItem(COORDS, Items.button(Material.COMPASS, Component.text("Coordinates", NamedTextColor.AQUA),
            List.of(hint("Pick dimension + X/Z + payload"))));
        inventory.setItem(SCHEDULED, Items.button(Material.CLOCK, Component.text("Scheduled strikes", NamedTextColor.AQUA),
            List.of(hint("Review / cancel pending launches"))));
        inventory.setItem(CLOSE, Items.button(Material.BARRIER, Component.text("Close", NamedTextColor.RED), List.of()));
        border();
        fillEmpty();
    }

    private Component hint(String s) { return Component.text(s, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false); }

    @Override
    public void onClick(InventoryClickEvent event) {
        event.setCancelled(true);
        Player p = (Player) event.getWhoClicked();
        switch (event.getRawSlot()) {
            case ROD -> new OrbitalPayloadGui(plugin, null, 0, 0).open(p);
            case COORDS -> new OrbitalDimensionGui(plugin).open(p);
            case SCHEDULED -> ScheduledStrikesGui.open(plugin, p);
            case CLOSE -> p.closeInventory();
        }
    }
}
