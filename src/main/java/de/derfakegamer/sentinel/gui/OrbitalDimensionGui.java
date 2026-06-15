package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.util.Items;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.util.ArrayList;
import java.util.List;

public final class OrbitalDimensionGui extends Gui {
    private static final int PAGE_SIZE = 45;
    private static final int BACK = 45, CLOSE = 53;

    private final List<World> worlds;

    public OrbitalDimensionGui(Sentinel plugin) {
        super(plugin);
        this.worlds = new ArrayList<>(Bukkit.getWorlds());
        this.inventory = Bukkit.createInventory(this, 54, plugin.messages().plain("gui-orbital-dim-title"));
        for (int i = 0; i < PAGE_SIZE && i < worlds.size(); i++) {
            World w = worlds.get(i);
            inventory.setItem(i, Items.button(iconFor(w), Component.text(w.getName(), NamedTextColor.AQUA),
                List.of(grey("Environment: " + w.getEnvironment().name()))));
        }
        inventory.setItem(BACK, Items.button(Material.ARROW, Component.text("Back", NamedTextColor.GRAY), List.of()));
        inventory.setItem(CLOSE, Items.button(Material.BARRIER, Component.text("Close", NamedTextColor.RED), List.of()));
        fillEmpty();
    }

    private Material iconFor(World w) {
        return switch (w.getEnvironment()) {
            case NETHER -> Material.NETHERRACK;
            case THE_END -> Material.END_STONE;
            default -> Material.GRASS_BLOCK;
        };
    }

    private Component grey(String s) { return Component.text(s, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false); }

    @Override
    public void onClick(InventoryClickEvent event) {
        event.setCancelled(true);
        Player p = (Player) event.getWhoClicked();
        int slot = event.getRawSlot();
        if (slot == BACK) { new OrbitalModeGui(plugin).open(p); return; }
        if (slot == CLOSE) { p.closeInventory(); return; }
        if (slot < 0 || slot >= PAGE_SIZE || slot >= worlds.size()) return;
        World world = worlds.get(slot);
        askCoord(p, world, "X", null);
    }

    /** Chat-prompts for X then Z, then opens the payload GUI in coordinate mode. */
    private void askCoord(Player p, World world, String axis, Integer x) {
        p.closeInventory();
        p.sendMessage(plugin.messages().prefixed("orbital-enter-coord", "axis", axis));
        plugin.chatInput().await(p.getUniqueId(), input -> {
            int value;
            try { value = Integer.parseInt(input.trim()); }
            catch (NumberFormatException e) { p.sendMessage(plugin.messages().prefixed("orbital-bad-coord")); return; }
            if (x == null) askCoord(p, world, "Z", value);              // got X, now ask Z
            else new OrbitalPayloadGui(plugin, world, x, value).open(p); // got Z, go pick payload
        });
    }
}
