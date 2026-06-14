package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.PunishmentType;
import de.derfakegamer.sentinel.util.Items;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.util.List;

public final class ReasonGui extends Gui {
    private static final int FIRST_PRESET = 10; // presets occupy 10..14
    private static final int CUSTOM = 22, CLOSE = 26;

    private final OfflinePlayer target;
    private final String ip;
    private final PunishmentType type;
    private final long expiresAt;
    private final List<String> presets;

    public ReasonGui(Sentinel plugin, OfflinePlayer target, String ip, PunishmentType type, long expiresAt) {
        super(plugin);
        this.target = target;
        this.ip = ip;
        this.type = type;
        this.expiresAt = expiresAt;
        this.presets = plugin.getConfig().getStringList("reasons");
        this.inventory = Bukkit.createInventory(this, 27, plugin.messages().plain("gui-reason-title"));
        for (int i = 0; i < 5; i++) {
            String label = i < presets.size() ? presets.get(i) : "—";
            inventory.setItem(FIRST_PRESET + i,
                Items.button(Material.PAPER, Component.text(label), List.of()));
        }
        inventory.setItem(CUSTOM, Items.button(Material.NAME_TAG,
            Component.text("Custom reason"), List.of(Component.text("Type it in chat"))));
        inventory.setItem(CLOSE, Items.button(Material.BARRIER, Component.text("Close"), List.of()));
        fillEmpty();
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        event.setCancelled(true);
        Player mod = (Player) event.getWhoClicked();
        int slot = event.getRawSlot();
        if (slot >= FIRST_PRESET && slot < FIRST_PRESET + 5) {
            int index = slot - FIRST_PRESET;
            if (index < presets.size()) openConfirm(mod, presets.get(index));
        } else if (slot == CUSTOM) {
            mod.closeInventory();
            mod.sendMessage(plugin.messages().prefixed("enter-reason"));
            plugin.chatInput().await(mod.getUniqueId(), reason -> openConfirm(mod, reason));
        } else if (slot == CLOSE) {
            mod.closeInventory();
        }
    }

    private void openConfirm(Player mod, String reason) {
        Component summary = plugin.messages().plain("confirm-summary",
            "type", type.name(), "player", target.getName() == null ? "?" : target.getName(), "reason", reason);
        Runnable action = () -> plugin.moderation().apply(
            mod.getUniqueId(), mod.getName(), target.getUniqueId(),
            target.getName() == null ? "?" : target.getName(), ip, type, expiresAt, reason);
        new ConfirmGui(plugin, summary, action, null).open(mod);
    }
}
