package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.OrbitalPayload;
import de.derfakegamer.sentinel.util.DurationParser;
import de.derfakegamer.sentinel.util.Items;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.util.List;

public final class OrbitalWhenGui extends Gui {
    private static final int NOW = 11, SCHEDULE = 15, CLOSE = 26;

    private final World world;
    private final int x, z;
    private final OrbitalPayload payload;

    public OrbitalWhenGui(Sentinel plugin, World world, int x, int z, OrbitalPayload payload) {
        super(plugin);
        this.world = world; this.x = x; this.z = z; this.payload = payload;
        this.inventory = Bukkit.createInventory(this, 27, plugin.secret().plain("gui-orbital-when-title"));
        inventory.setItem(NOW, Items.button(Material.FIRE_CHARGE, Component.text("Strike now", NamedTextColor.RED),
            List.of(hint("Fire immediately"))));
        inventory.setItem(SCHEDULE, Items.button(Material.CLOCK, Component.text("Schedule", NamedTextColor.AQUA),
            List.of(hint("Fire after a delay"))));
        inventory.setItem(CLOSE, Items.button(Material.BARRIER, Component.text("Close", NamedTextColor.RED), List.of()));
        border();
        fillEmpty();
    }

    private static Component hint(String text) {
        return Component.text(text, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false);
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        event.setCancelled(true);
        Player p = (Player) event.getWhoClicked();
        String worldName = world.getName();
        switch (event.getRawSlot()) {
            case NOW -> {
                Component summary = Component.text("Strike: " + payload.label(), NamedTextColor.AQUA);
                Runnable action = () -> {
                    World w = Bukkit.getWorld(worldName);
                    if (w == null) { p.sendMessage(plugin.secret().prefixed("orbital-world-gone")); return; }
                    plugin.orbital().strike(w, x, z, payload);
                    p.sendMessage(plugin.secret().prefixed("orbital-fired",
                        "x", String.valueOf(x), "z", String.valueOf(z)));
                };
                new ConfirmGui(plugin, summary, action, null).open(p);
            }
            case SCHEDULE -> {
                p.closeInventory();
                p.sendMessage(plugin.secret().prefixed("orbital-enter-delay"));
                plugin.chatInput().await(p.getUniqueId(), input -> {
                    long ms;
                    try { ms = DurationParser.parse(input); }
                    catch (IllegalArgumentException ex) { p.sendMessage(plugin.secret().prefixed("orbital-bad-coord")); return; }
                    World w = Bukkit.getWorld(worldName);
                    if (w == null) { p.sendMessage(plugin.secret().prefixed("orbital-world-gone")); return; }
                    String inputCopy = input;
                    plugin.db().callback(plugin.scheduledStrikes().schedule(w, x, z, payload, System.currentTimeMillis() + ms),
                        id -> p.sendMessage(plugin.secret().prefixed("orbital-scheduled", "time", inputCopy)));
                });
            }
            case CLOSE -> p.closeInventory();
        }
    }
}
