package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.OrbitalPayload;
import de.derfakegamer.sentinel.util.Items;
import de.derfakegamer.sentinel.util.OrbitalRod;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.util.List;

public final class OrbitalPayloadGui extends Gui {
    private static final int TNT = 11, CART = 13, CREEPER = 15, BACK = 18, CLOSE = 26;

    private final World world; // null = rod mode
    private final int x, z;

    public OrbitalPayloadGui(Sentinel plugin, World world, int x, int z) {
        super(plugin);
        this.world = world; this.x = x; this.z = z;
        this.inventory = Bukkit.createInventory(this, 27, plugin.messages().plain("gui-orbital-payload-title"));
        inventory.setItem(TNT, button(Material.TNT, OrbitalPayload.TNT));
        inventory.setItem(CART, button(Material.TNT_MINECART, OrbitalPayload.TNT_MINECART));
        inventory.setItem(CREEPER, button(Material.CREEPER_HEAD, OrbitalPayload.CHARGED_CREEPER));
        inventory.setItem(BACK, Items.button(Material.ARROW, Component.text("Back", NamedTextColor.GRAY), List.of()));
        inventory.setItem(CLOSE, Items.button(Material.BARRIER, Component.text("Close", NamedTextColor.RED), List.of()));
        fillEmpty();
    }

    private org.bukkit.inventory.ItemStack button(Material m, OrbitalPayload payload) {
        return Items.button(m, Component.text(payload.label(), NamedTextColor.AQUA), List.of());
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        event.setCancelled(true);
        Player p = (Player) event.getWhoClicked();
        OrbitalPayload payload = switch (event.getRawSlot()) {
            case TNT -> OrbitalPayload.TNT;
            case CART -> OrbitalPayload.TNT_MINECART;
            case CREEPER -> OrbitalPayload.CHARGED_CREEPER;
            default -> null;
        };
        if (event.getRawSlot() == BACK) { new OrbitalModeGui(plugin).open(p); return; }
        if (event.getRawSlot() == CLOSE) { p.closeInventory(); return; }
        if (payload == null) return;

        if (world != null) {
            new OrbitalWhenGui(plugin, world, x, z, payload).open(p);
            return;
        }

        Component summary = Component.text("Give rod: " + payload.label(), NamedTextColor.AQUA);
        Runnable action = () -> { p.getInventory().addItem(OrbitalRod.create(plugin, payload));
                      p.sendMessage(plugin.messages().prefixed("orbital-rod-received")); };
        new ConfirmGui(plugin, summary, action, null).open(p);
    }
}
