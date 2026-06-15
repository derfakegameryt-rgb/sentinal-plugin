package de.derfakegamer.sentinel.util;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.OrbitalPayload;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;

public final class OrbitalRod {
    private OrbitalRod() {}

    private static NamespacedKey key(Sentinel plugin) { return new NamespacedKey(plugin, "orbital_payload"); }

    /** A fishing rod with one durability left, tagged with the payload. */
    public static ItemStack create(Sentinel plugin, OrbitalPayload payload) {
        ItemStack rod = new ItemStack(Material.FISHING_ROD);
        rod.editMeta(meta -> {
            meta.displayName(Component.text("Orbital Strike", NamedTextColor.RED)
                .decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(
                grey("Payload: " + payload.label()),
                grey("Right-click to fire at your crosshair")));
            if (meta instanceof Damageable d) d.setDamage(Material.FISHING_ROD.getMaxDurability() - 1);
            meta.getPersistentDataContainer().set(key(plugin), PersistentDataType.STRING, payload.name());
        });
        return rod;
    }

    /** The payload tagged on this item, or null if it isn't an orbital rod. */
    public static OrbitalPayload payloadOf(Sentinel plugin, ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        String s = item.getItemMeta().getPersistentDataContainer().get(key(plugin), PersistentDataType.STRING);
        if (s == null) return null;
        try { return OrbitalPayload.valueOf(s); } catch (IllegalArgumentException e) { return null; }
    }

    private static Component grey(String s) {
        return Component.text(s, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false);
    }
}
