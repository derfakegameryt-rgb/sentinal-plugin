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

public final class OwnerPanelGui extends Gui {
    private static final int USERS = 11, CODE = 15, CLOSE = 26;

    public OwnerPanelGui(Sentinel plugin) {
        super(plugin);
        this.inventory = Bukkit.createInventory(this, 27, plugin.secret().plain("gui-owner-title"));
        inventory.setItem(USERS, button(Material.PLAYER_HEAD, "Orbital users", "Add or remove who may strike"));
        inventory.setItem(CODE, button(Material.TRIPWIRE_HOOK, "Change code", "Set a new keypad code"));
        inventory.setItem(CLOSE, Items.button(Material.BARRIER, Component.text("Close", NamedTextColor.RED), List.of()));
        fillEmpty();
    }

    private org.bukkit.inventory.ItemStack button(Material m, String title, String hint) {
        return Items.button(m, Component.text(title, NamedTextColor.AQUA),
            List.of(Component.text(hint, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        event.setCancelled(true);
        Player p = (Player) event.getWhoClicked();
        switch (event.getRawSlot()) {
            case USERS -> new OrbitalUsersGui(plugin).open(p);
            case CODE -> {
                p.closeInventory();
                p.sendMessage(plugin.secret().prefixed("owner-enter-code"));
                plugin.chatInput().await(p.getUniqueId(), code -> {
                    if (!code.matches("\\d{4}")) { p.sendMessage(plugin.secret().prefixed("owner-bad-code")); return; }
                    plugin.orbitalAccess().setCode(code);
                    p.sendMessage(plugin.secret().prefixed("owner-code-changed"));
                });
            }
            case CLOSE -> p.closeInventory();
        }
    }
}
