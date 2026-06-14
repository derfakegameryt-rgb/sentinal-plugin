package de.derfakegamer.sentinel.util;

import net.kyori.adventure.text.Component;
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
            meta.displayName(name.decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
            if (lore != null && !lore.isEmpty()) meta.lore(lore);
        });
        return item;
    }

    public static ItemStack head(OfflinePlayer owner, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        item.editMeta(SkullMeta.class, meta -> {
            meta.setOwningPlayer(owner);
            meta.displayName(name.decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
            if (lore != null && !lore.isEmpty()) meta.lore(lore);
        });
        return item;
    }

    public static ItemStack filler() {
        return button(Material.LIGHT_BLUE_STAINED_GLASS_PANE, Component.text(" "), null);
    }
}
