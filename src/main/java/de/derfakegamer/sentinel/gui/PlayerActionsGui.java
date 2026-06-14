package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.PunishmentType;
import de.derfakegamer.sentinel.util.DurationParser;
import de.derfakegamer.sentinel.util.Items;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.util.List;

public final class PlayerActionsGui extends Gui {
    private static final int HEAD = 4;
    private static final int BAN = 10, TEMPBAN = 11, MUTE = 12, TEMPMUTE = 13, KICK = 14, WARN = 15;
    private static final int IPBAN = 19, HISTORY = 22, BACK = 36, CLOSE = 44;

    private final OfflinePlayer target;
    private final boolean banned;
    private final boolean muted;

    public PlayerActionsGui(Sentinel plugin, OfflinePlayer target) {
        super(plugin);
        this.target = target;
        long now = System.currentTimeMillis();
        this.banned = plugin.punishments().activeBan(target.getUniqueId(), now) != null;
        this.muted = plugin.punishments().activeMute(target.getUniqueId(), now) != null;
        this.inventory = Bukkit.createInventory(this, 45,
            plugin.messages().plain("gui-actions-title", "player", name()));

        inventory.setItem(HEAD, Items.head(target, Component.text(name()),
            List.of(Component.text(banned ? "Banned" : "Not banned"),
                    Component.text(muted ? "Muted" : "Not muted"),
                    Component.text("Warns: " + plugin.punishments().warnCount(target.getUniqueId())))));

        inventory.setItem(BAN, Items.button(Material.BARRIER,
            Component.text(banned ? "Unban" : "Ban"), List.of()));
        inventory.setItem(TEMPBAN, Items.button(Material.CLOCK, Component.text("Tempban"), List.of()));
        inventory.setItem(MUTE, Items.button(Material.BOOK,
            Component.text(muted ? "Unmute" : "Mute"), List.of()));
        inventory.setItem(TEMPMUTE, Items.button(Material.CLOCK, Component.text("Tempmute"), List.of()));
        inventory.setItem(KICK, Items.button(Material.LEATHER_BOOTS, Component.text("Kick"), List.of()));
        inventory.setItem(WARN, Items.button(Material.YELLOW_BANNER, Component.text("Warn"), List.of()));
        if (target.isOnline())
            inventory.setItem(IPBAN, Items.button(Material.IRON_BARS, Component.text("IP-Ban"), List.of()));
        inventory.setItem(HISTORY, Items.button(Material.WRITABLE_BOOK, Component.text("History"), List.of()));
        inventory.setItem(BACK, Items.button(Material.ARROW, Component.text("Back"), List.of()));
        inventory.setItem(CLOSE, Items.button(Material.BARRIER, Component.text("Close"), List.of()));
        fillEmpty();
    }

    private String name() { return target.getName() == null ? "?" : target.getName(); }

    private String ip() {
        Player online = target.getPlayer();
        return (online != null && online.getAddress() != null)
            ? online.getAddress().getAddress().getHostAddress() : null;
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        event.setCancelled(true);
        Player mod = (Player) event.getWhoClicked();
        switch (event.getRawSlot()) {
            case BAN -> {
                if (banned) { plugin.moderation().removeBan(mod.getUniqueId(), mod.getName(), target.getUniqueId(), name()); mod.closeInventory(); }
                else new ReasonGui(plugin, target, null, PunishmentType.BAN, 0).open(mod);
            }
            case MUTE -> {
                if (muted) { plugin.moderation().removeMute(mod.getUniqueId(), mod.getName(), target.getUniqueId(), name()); mod.closeInventory(); }
                else new ReasonGui(plugin, target, null, PunishmentType.MUTE, 0).open(mod);
            }
            case TEMPBAN -> awaitDuration(mod, PunishmentType.BAN);
            case TEMPMUTE -> awaitDuration(mod, PunishmentType.MUTE);
            case KICK -> new ReasonGui(plugin, target, null, PunishmentType.KICK, 0).open(mod);
            case WARN -> new ReasonGui(plugin, target, null, PunishmentType.WARN, 0).open(mod);
            case IPBAN -> {
                String ip = ip();
                if (ip != null) new ReasonGui(plugin, target, ip, PunishmentType.IPBAN, 0).open(mod);
                else mod.sendMessage(plugin.messages().prefixed("ipban-requires-online"));
            }
            case HISTORY -> new HistoryGui(plugin, target, 0).open(mod);
            case BACK -> new PlayersGui(plugin, 0).open(mod);
            case CLOSE -> mod.closeInventory();
        }
    }

    private void awaitDuration(Player mod, PunishmentType type) {
        mod.closeInventory();
        mod.sendMessage(plugin.messages().prefixed("enter-duration"));
        plugin.chatInput().await(mod.getUniqueId(), input -> {
            long expiresAt;
            try { expiresAt = System.currentTimeMillis() + DurationParser.parse(input); }
            catch (IllegalArgumentException e) { mod.sendMessage(plugin.messages().prefixed("bad-duration")); return; }
            new ReasonGui(plugin, target, null, type, expiresAt).open(mod);
        });
    }
}
