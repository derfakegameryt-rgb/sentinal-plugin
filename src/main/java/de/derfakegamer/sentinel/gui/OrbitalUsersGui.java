package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.PlayerRecord;
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
import java.util.Map;
import java.util.UUID;

public final class OrbitalUsersGui extends Gui {
    private static final int PAGE_SIZE = 45;
    private static final int ADD = 49, BACK = 45, CLOSE = 53;

    private final List<Map.Entry<UUID, String>> users;

    public OrbitalUsersGui(Sentinel plugin) {
        super(plugin);
        this.users = new ArrayList<>(plugin.orbitalAccess().list().entrySet());
        this.inventory = Bukkit.createInventory(this, 54, plugin.messages().plain("gui-orbital-users-title"));
        for (int i = 0; i < PAGE_SIZE && i < users.size(); i++) {
            var e = users.get(i);
            inventory.setItem(i, Items.head(Bukkit.getOfflinePlayer(e.getKey()),
                Component.text(e.getValue(), NamedTextColor.AQUA),
                List.of(Component.text("Click to remove", NamedTextColor.RED).decoration(TextDecoration.ITALIC, false))));
        }
        inventory.setItem(ADD, Items.button(Material.LIME_DYE, Component.text("Add player", NamedTextColor.GREEN),
            List.of(Component.text("Type a name in chat", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false))));
        inventory.setItem(BACK, Items.button(Material.ARROW, Component.text("Back", NamedTextColor.GRAY), List.of()));
        inventory.setItem(CLOSE, Items.button(Material.BARRIER, Component.text("Close", NamedTextColor.RED), List.of()));
        fillEmpty();
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        event.setCancelled(true);
        Player p = (Player) event.getWhoClicked();
        int slot = event.getRawSlot();
        if (slot == BACK) { new OwnerPanelGui(plugin).open(p); return; }
        if (slot == CLOSE) { p.closeInventory(); return; }
        if (slot == ADD) {
            p.closeInventory();
            p.sendMessage(plugin.messages().prefixed("owner-enter-user"));
            plugin.chatInput().await(p.getUniqueId(), name -> {
                PlayerRecord rec = plugin.players().byName(name);
                UUID id = rec != null ? rec.uuid() : Bukkit.getOfflinePlayer(name).getUniqueId();
                plugin.orbitalAccess().add(id, name);
                Player online = Bukkit.getPlayer(id);
                if (online != null) plugin.orbitalAccessListener().apply(online);
                p.sendMessage(plugin.messages().prefixed("owner-user-added", "player", name));
            });
            return;
        }
        if (slot >= 0 && slot < PAGE_SIZE && slot < users.size()) {
            var e = users.get(slot);
            plugin.orbitalAccess().remove(e.getKey());
            Player online = Bukkit.getPlayer(e.getKey());
            if (online != null) plugin.orbitalAccessListener().apply(online);
            p.sendMessage(plugin.messages().prefixed("owner-user-removed", "player", e.getValue()));
            new OrbitalUsersGui(plugin).open(p);
        }
    }
}
