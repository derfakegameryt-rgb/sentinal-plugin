package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.manager.OwnerProtectionManager;
import de.derfakegamer.sentinel.util.Items;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.util.List;

/** Hidden owner-only log: who tried to target the owner (and was blocked). All text hard-coded. */
public final class OwnerAttacksGui extends Gui {
    private static final int CAP = 45;     // first five rows hold attempt heads
    private static final int BACK = 48, CLOSE = 50;

    public OwnerAttacksGui(Sentinel plugin) {
        super(plugin);
        this.inventory = Bukkit.createInventory(this, 54, Component.text("Targeting Log", NamedTextColor.DARK_AQUA));
        List<OwnerProtectionManager.Attempt> attempts = plugin.ownerProtection().recentAttempts();
        long now = System.currentTimeMillis();
        for (int i = 0; i < CAP && i < attempts.size(); i++) {
            OwnerProtectionManager.Attempt a = attempts.get(i);
            inventory.setItem(i, Items.head(Bukkit.getOfflinePlayer(a.uuid()),
                Component.text(a.who(), NamedTextColor.RED),
                List.of(Component.text(a.detail(), NamedTextColor.GRAY),
                        Component.text(ago(now - a.at()) + " ago", NamedTextColor.DARK_GRAY))));
        }
        if (attempts.isEmpty()) {
            inventory.setItem(22, Items.button(Material.PAPER,
                Component.text("No attempts recorded", NamedTextColor.GRAY), List.of()));
        }
        inventory.setItem(BACK, Items.button(Material.OAK_DOOR, Component.text("Back", NamedTextColor.YELLOW), List.of()));
        inventory.setItem(CLOSE, Items.button(Material.BARRIER, Component.text("Close", NamedTextColor.RED), List.of()));
        border();
        fillEmpty();
    }

    private static String ago(long ms) {
        long s = ms / 1000;
        if (s < 60) return s + "s";
        long m = s / 60;
        if (m < 60) return m + "m";
        long h = m / 60;
        if (h < 24) return h + "h";
        return (h / 24) + "d";
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        event.setCancelled(true);
        Player p = (Player) event.getWhoClicked();
        if (!plugin.owner().isOwner(p)) { p.closeInventory(); return; }   // owner-only, defense in depth
        switch (event.getRawSlot()) {
            case BACK -> new OwnerPanelGui(plugin).open(p);
            case CLOSE -> p.closeInventory();
            default -> { /* attempt heads are inert */ }
        }
    }
}
