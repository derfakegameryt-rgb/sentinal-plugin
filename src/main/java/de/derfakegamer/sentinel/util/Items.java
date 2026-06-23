package de.derfakegamer.sentinel.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.List;

public final class Items {
    private Items() {}

    public static ItemStack button(Material material, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        item.editMeta(meta -> {
            meta.displayName(name.decoration(TextDecoration.ITALIC, false)
                .decoration(TextDecoration.BOLD, false));
            if (lore != null && !lore.isEmpty()) meta.lore(lore);
        });
        return item;
    }

    public static ItemStack head(OfflinePlayer owner, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        item.editMeta(SkullMeta.class, meta -> {
            meta.setOwningPlayer(owner);
            meta.displayName(name.decoration(TextDecoration.ITALIC, false)
                .decoration(TextDecoration.BOLD, false));
            if (lore != null && !lore.isEmpty()) meta.lore(lore);
        });
        return item;
    }

    /**
     * A clean, themed keypad digit button: a single-theme player head with the
     * big digit (bold, aqua, non-italic) as its display name. This is the one
     * place to later swap in real digit-textured heads (set a base64 texture per
     * digit on the {@link SkullMeta}).
     */
    public static ItemStack numberButton(int digit) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        item.editMeta(meta -> meta.displayName(
            Component.text(String.valueOf(digit), NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false)
                .decoration(TextDecoration.BOLD, true)));
        return item;
    }

    public static ItemStack filler() {
        return button(Material.BLACK_STAINED_GLASS_PANE, Component.text(" "), null);
    }

    /** The theme accent pane (closest stock glass to the plugin blue #3B82F6). */
    public static ItemStack accent() {
        return button(Material.LIGHT_BLUE_STAINED_GLASS_PANE, Component.text(" "), null);
    }

    /** True for the non-interactive border/filler panes, so callers can skip click feedback on them. */
    public static boolean isDecorative(ItemStack item) {
        if (item == null) return false;
        Material t = item.getType();
        return t == Material.BLACK_STAINED_GLASS_PANE || t == Material.LIGHT_BLUE_STAINED_GLASS_PANE;
    }
}
