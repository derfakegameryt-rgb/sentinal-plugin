package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.ScheduledStrike;
import de.derfakegamer.sentinel.util.Items;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.util.ArrayList;
import java.util.List;

public final class ScheduledStrikesGui extends Gui {
    private static final int PAGE_SIZE = 45;
    private static final int BACK = 45, CLOSE = 53;

    private final List<ScheduledStrike> strikes;

    public ScheduledStrikesGui(Sentinel plugin) {
        super(plugin);
        this.strikes = new ArrayList<>(plugin.scheduledStrikes().pending());
        this.inventory = Bukkit.createInventory(this, 54, plugin.secret().plain("gui-scheduled-title"));
        for (int i = 0; i < PAGE_SIZE && i < strikes.size(); i++) {
            ScheduledStrike s = strikes.get(i);
            inventory.setItem(i, Items.button(Material.CLOCK,
                Component.text(s.payload().label(), NamedTextColor.AQUA),
                List.of(
                    lore("World: " + s.world()),
                    lore("At: " + s.x() + "," + s.z()),
                    lore("Payload: " + s.payload().label()),
                    lore("Fires in: " + remaining(s.fireAt())),
                    Component.text("Click to cancel", NamedTextColor.RED).decoration(TextDecoration.ITALIC, false))));
        }
        inventory.setItem(BACK, Items.button(Material.ARROW, Component.text("Back", NamedTextColor.GRAY), List.of()));
        inventory.setItem(CLOSE, Items.button(Material.BARRIER, Component.text("Close", NamedTextColor.RED), List.of()));
        fillEmpty();
    }

    private static Component lore(String text) {
        return Component.text(text, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false);
    }

    private static String remaining(long fireAt) {
        long ms = fireAt - System.currentTimeMillis();
        if (ms <= 0) return "now";
        long s = ms / 1000;
        long h = s / 3600; s %= 3600;
        long m = s / 60; s %= 60;
        StringBuilder sb = new StringBuilder();
        if (h > 0) sb.append(h).append("h ");
        if (m > 0) sb.append(m).append("m ");
        sb.append(s).append("s");
        return sb.toString().trim();
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        event.setCancelled(true);
        Player p = (Player) event.getWhoClicked();
        int slot = event.getRawSlot();
        if (slot == BACK) { new OrbitalModeGui(plugin).open(p); return; }
        if (slot == CLOSE) { p.closeInventory(); return; }
        if (slot >= 0 && slot < PAGE_SIZE && slot < strikes.size()) {
            ScheduledStrike s = strikes.get(slot);
            plugin.scheduledStrikes().cancel(s.id());
            p.sendMessage(plugin.secret().prefixed("scheduled-cancelled"));
            new ScheduledStrikesGui(plugin).open(p);
        }
    }
}
