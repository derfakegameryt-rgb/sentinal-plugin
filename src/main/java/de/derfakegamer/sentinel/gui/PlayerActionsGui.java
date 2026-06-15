package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.PunishmentType;
import de.derfakegamer.sentinel.util.DurationParser;
import de.derfakegamer.sentinel.util.Items;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.util.List;

public final class PlayerActionsGui extends Gui {
    private static final int HEAD = 4;
    private static final int BAN = 10, TEMPBAN = 11, MUTE = 12, TEMPMUTE = 13, KICK = 14, WARN = 15;
    private static final int SHADOWMUTE_SLOT = 16;
    private static final int LOGS = 17;
    private static final int TEMPLATES = 27;
    private static final int IPBAN = 19, FREEZE = 20, INVSEE = 21, ECHEST = 22, HISTORY = 23, NOTES = 24, ALTS = 25, OPTOGGLE = 26, BACK = 36, CLOSE = 44;

    private final OfflinePlayer target;
    private final boolean banned;
    private final boolean muted;
    private final boolean shadowMuted;

    public PlayerActionsGui(Sentinel plugin, OfflinePlayer target) {
        super(plugin);
        this.target = target;
        long now = System.currentTimeMillis();
        this.banned = plugin.punishments().activeBan(target.getUniqueId(), now) != null;
        this.muted = plugin.punishments().activeMute(target.getUniqueId(), now) != null;
        this.shadowMuted = plugin.punishments().activeShadowMute(target.getUniqueId(), now) != null;
        this.inventory = Bukkit.createInventory(this, 45,
            plugin.messages().plain("gui-actions-title", "player", name()));

        inventory.setItem(HEAD, Items.head(target, Component.text(name(), NamedTextColor.AQUA),
            List.of(status(banned ? "Banned" : "Not banned", banned),
                    status(muted ? "Muted" : "Not muted", muted),
                    line("Warns: " + plugin.punishments().warnCount(target.getUniqueId()), NamedTextColor.GRAY))));

        inventory.setItem(BAN, Items.button(Material.BARRIER,
            Component.text(banned ? "Unban" : "Ban", banned ? NamedTextColor.GREEN : NamedTextColor.RED),
            List.of(hint(banned ? "Remove this player's ban" : "Permanently ban this player"))));
        inventory.setItem(TEMPBAN, Items.button(Material.CLOCK, Component.text("Tempban", NamedTextColor.GOLD),
            List.of(hint("Ban for a set duration"))));
        inventory.setItem(MUTE, Items.button(Material.BOOK,
            Component.text(muted ? "Unmute" : "Mute", muted ? NamedTextColor.GREEN : NamedTextColor.RED),
            List.of(hint(muted ? "Remove this player's mute" : "Prevent this player from chatting"))));
        inventory.setItem(TEMPMUTE, Items.button(Material.CLOCK, Component.text("Tempmute", NamedTextColor.GOLD),
            List.of(hint("Mute for a set duration"))));
        inventory.setItem(KICK, Items.button(Material.LEATHER_BOOTS, Component.text("Kick", NamedTextColor.RED),
            List.of(hint("Disconnect this player now"))));
        inventory.setItem(WARN, Items.button(Material.YELLOW_BANNER, Component.text("Warn", NamedTextColor.YELLOW),
            List.of(hint("Issue a formal warning"))));
        inventory.setItem(SHADOWMUTE_SLOT, Items.button(Material.INK_SAC,
            net.kyori.adventure.text.Component.text(shadowMuted ? "Un-shadowmute" : "Shadow-mute",
                shadowMuted ? net.kyori.adventure.text.format.NamedTextColor.GREEN
                            : net.kyori.adventure.text.format.NamedTextColor.LIGHT_PURPLE),
            List.of(net.kyori.adventure.text.Component.text("Covert mute — only they see their chat",
                net.kyori.adventure.text.format.NamedTextColor.GRAY)
                .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false))));
        inventory.setItem(LOGS, Items.button(Material.WRITTEN_BOOK, Component.text("Chat logs", NamedTextColor.AQUA),
            List.of(hint("View recent chat & commands"))));
        inventory.setItem(TEMPLATES, Items.button(Material.WRITABLE_BOOK, Component.text("Templates", NamedTextColor.AQUA),
            List.of(hint("Quick preset punishments"))));
        if (ip() != null) {
            inventory.setItem(IPBAN, Items.button(Material.IRON_BARS, Component.text("IP-Ban", NamedTextColor.DARK_RED),
                List.of(hint("Ban the last known IP"))));
        }
        if (target.isOnline()) {
            boolean frozen = plugin.freeze().isFrozen(target.getUniqueId());
            inventory.setItem(FREEZE, Items.button(Material.ICE,
                Component.text(frozen ? "Unfreeze" : "Freeze", frozen ? NamedTextColor.GREEN : NamedTextColor.AQUA),
                List.of(hint(frozen ? "Allow this player to move" : "Stop this player from moving"))));
            inventory.setItem(INVSEE, Items.button(Material.CHEST, Component.text("View inventory", NamedTextColor.AQUA),
                List.of(hint("Open this player's inventory"))));
            inventory.setItem(ECHEST, Items.button(Material.ENDER_CHEST, Component.text("View ender chest", NamedTextColor.LIGHT_PURPLE),
                List.of(hint("Open this player's ender chest"))));
        }
        inventory.setItem(HISTORY, Items.button(Material.WRITABLE_BOOK, Component.text("History", NamedTextColor.AQUA),
            List.of(hint("View past punishments"))));
        inventory.setItem(NOTES, Items.button(Material.BOOK, Component.text("Notes", NamedTextColor.AQUA),
            List.of(hint("Staff notes about this player"))));
        inventory.setItem(ALTS, Items.button(Material.PLAYER_HEAD, Component.text("Alts", NamedTextColor.AQUA),
            List.of(hint("Accounts sharing this IP"))));
        inventory.setItem(OPTOGGLE, Items.button(target.isOp() ? Material.NETHERITE_BLOCK : Material.NETHERITE_SCRAP,
            net.kyori.adventure.text.Component.text(target.isOp() ? "De-OP" : "Make OP",
                target.isOp() ? net.kyori.adventure.text.format.NamedTextColor.RED
                               : net.kyori.adventure.text.format.NamedTextColor.GREEN),
            List.of(net.kyori.adventure.text.Component.text("Toggle operator status", net.kyori.adventure.text.format.NamedTextColor.GRAY)
                .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false))));
        inventory.setItem(BACK, Items.button(Material.ARROW, Component.text("Back", NamedTextColor.GRAY),
            List.of(hint("Return to the player list"))));
        inventory.setItem(CLOSE, Items.button(Material.BARRIER, Component.text("Close", NamedTextColor.RED),
            List.of(hint("Close this menu"))));
        fillEmpty();
    }

    private static Component hint(String text) {
        return Component.text(text, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false);
    }

    private static Component line(String text, NamedTextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }

    private static Component status(String text, boolean active) {
        return line(text, active ? NamedTextColor.RED : NamedTextColor.GREEN);
    }

    private String name() { return target.getName() == null ? "?" : target.getName(); }

    private String ip() {
        Player online = target.getPlayer();
        if (online != null && online.getAddress() != null)
            return online.getAddress().getAddress().getHostAddress();
        var rec = plugin.players().byUuid(target.getUniqueId());
        return rec != null ? rec.lastIp() : null;
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
            case SHADOWMUTE_SLOT -> {
                if (shadowMuted) { plugin.moderation().removeShadowMute(mod.getUniqueId(), mod.getName(), target.getUniqueId(), name()); mod.closeInventory(); }
                else new ReasonGui(plugin, target, null, PunishmentType.SHADOWMUTE, 0).open(mod);
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
            case FREEZE -> {
                Player online = target.getPlayer();
                if (online != null) {
                    boolean nowFrozen = plugin.freeze().toggle(target.getUniqueId());
                    if (nowFrozen) online.sendMessage(plugin.messages().prefixed("you-are-frozen"));
                    mod.sendMessage(plugin.messages().prefixed(nowFrozen ? "freeze-frozen" : "freeze-unfrozen", "player", name()));
                    mod.closeInventory();
                }
            }
            case INVSEE -> {
                Player online = target.getPlayer();
                if (online != null) new InvseeGui(plugin, online).open(mod);
            }
            case ECHEST -> {
                Player online = target.getPlayer();
                if (online != null) mod.openInventory(online.getEnderChest());
            }
            case LOGS -> new ChatLogGui(plugin, target).open(mod);
            case TEMPLATES -> new TemplatesGui(plugin, target).open(mod);
            case HISTORY -> new HistoryGui(plugin, target, 0).open(mod);
            case NOTES -> new NotesGui(plugin, target).open(mod);
            case ALTS -> new AltsGui(plugin, target).open(mod);
            case OPTOGGLE -> {
                // Protected players (config `exempt`, e.g. the owner) cannot be de-opped via the panel.
                if (target.isOp() && plugin.punishments().isExempt(target.getUniqueId())) {
                    mod.sendMessage(plugin.messages().prefixed("exempt"));
                    return;
                }
                boolean makeOp = !target.isOp();
                target.setOp(makeOp);
                mod.sendMessage(plugin.messages().prefixed(makeOp ? "opped" : "deopped", "player", name()));
                mod.closeInventory();
            }
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
