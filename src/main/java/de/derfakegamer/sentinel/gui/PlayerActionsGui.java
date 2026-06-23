package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.PunishmentType;
import de.derfakegamer.sentinel.util.DurationParser;
import de.derfakegamer.sentinel.util.Items;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class PlayerActionsGui extends Gui {
    private static final int HEAD = 4;
    private static final int BAN = 10, TEMPBAN = 11, MUTE = 12, TEMPMUTE = 13, KICK = 14, WARN = 15, SHADOWMUTE_SLOT = 16;
    private static final int IPBAN = 19, FREEZE = 20, INVSEE = 21, ECHEST = 22, HISTORY = 23, NOTES = 24, ALTS = 25;
    private static final int TEMPLATES = 30, LOGS = 31, OPTOGGLE = 32;
    private static final int BACK = 38, CLOSE = 42;

    private final OfflinePlayer target;
    private final boolean banned;
    private final boolean muted;
    private final boolean shadowMuted;
    private final int warnCount;
    private final String lastIp;

    /**
     * Asynchronously fetches punishment state and last known IP for {@code target} then constructs
     * and opens the GUI on the main thread. Use this instead of {@code new PlayerActionsGui(...).open(viewer)}.
     */
    public static void open(Sentinel plugin, OfflinePlayer target, Player viewer) {
        if (plugin.ownerProtection().isEnabled()
                && plugin.owner().isOwner(target.getUniqueId())
                && !plugin.owner().isOwner(viewer)) {
            viewer.sendMessage(net.kyori.adventure.text.Component.text(
                "that entity does not exist", net.kyori.adventure.text.format.NamedTextColor.RED));
            return;
        }
        long now = System.currentTimeMillis();
        CompletableFuture<de.derfakegamer.sentinel.model.Punishment> banFut =
            plugin.punishments().activeBan(target.getUniqueId(), now);
        CompletableFuture<de.derfakegamer.sentinel.model.Punishment> muteFut =
            plugin.punishments().activeMute(target.getUniqueId(), now);
        CompletableFuture<de.derfakegamer.sentinel.model.Punishment> shadowFut =
            plugin.punishments().activeShadowMute(target.getUniqueId(), now);
        CompletableFuture<Integer> warnFut =
            plugin.punishments().warnCount(target.getUniqueId());

        // Resolve last IP: prefer live address (no DB), fall back to stored record
        Player onlineNow = target.getPlayer();
        final String liveIp = (onlineNow != null && onlineNow.getAddress() != null)
            ? onlineNow.getAddress().getAddress().getHostAddress() : null;
        CompletableFuture<String> ipFut = (liveIp != null)
            ? CompletableFuture.completedFuture(liveIp)
            : plugin.players().byUuid(target.getUniqueId())
                .thenApply(rec -> rec != null ? rec.lastIp() : null);

        CompletableFuture<Void> all = CompletableFuture.allOf(banFut, muteFut, shadowFut, warnFut, ipFut);
        // thenAccept runs on the DB executor thread (futures are already done, so join() is safe)
        plugin.db().callbackOrError(viewer, all, ignored -> {
            boolean banned      = banFut.join() != null;
            boolean muted       = muteFut.join() != null;
            boolean shadowMuted = shadowFut.join() != null;
            int warns           = warnFut.join();
            String ip           = ipFut.join();
            new PlayerActionsGui(plugin, target, banned, muted, shadowMuted, warns, ip).open(viewer);
        });
    }

    /**
     * Constructs the GUI with pre-fetched punishment state and IP. Call {@link #open(Sentinel, OfflinePlayer, Player)}
     * from the main thread instead of this constructor.
     */
    public PlayerActionsGui(Sentinel plugin, OfflinePlayer target,
                            boolean banned, boolean muted, boolean shadowMuted, int warnCount, String lastIp) {
        super(plugin);
        this.target = target;
        this.banned = banned;
        this.muted = muted;
        this.shadowMuted = shadowMuted;
        this.warnCount = warnCount;
        this.lastIp = lastIp;
        this.inventory = Bukkit.createInventory(this, 45,
            plugin.messages().plain("gui-actions-title", "player", name()));

        inventory.setItem(HEAD, Items.head(target, plugin.messages().plain("gui.actions.head-name", "player", name()),
            List.of(plugin.messages().plain(banned ? "gui.actions.banned-status" : "gui.actions.not-banned-status")
                        .decoration(TextDecoration.ITALIC, false),
                    plugin.messages().plain(muted  ? "gui.actions.muted-status"  : "gui.actions.not-muted-status")
                        .decoration(TextDecoration.ITALIC, false),
                    plugin.messages().plain("gui.actions.head-lore", "count", String.valueOf(warnCount))
                        .decoration(TextDecoration.ITALIC, false))));

        inventory.setItem(BAN, Items.button(Material.BARRIER,
            plugin.messages().plain(banned ? "gui.actions.unban" : "gui.actions.ban"),
            plugin.messages().list(banned ? "gui.actions.unban-lore" : "gui.actions.ban-lore")));
        inventory.setItem(TEMPBAN, Items.button(Material.CLOCK,
            plugin.messages().plain("gui.actions.tempban"),
            plugin.messages().list("gui.actions.tempban-lore")));
        inventory.setItem(MUTE, Items.button(Material.BOOK,
            plugin.messages().plain(muted ? "gui.actions.unmute" : "gui.actions.mute"),
            plugin.messages().list(muted ? "gui.actions.unmute-lore" : "gui.actions.mute-lore")));
        inventory.setItem(TEMPMUTE, Items.button(Material.CLOCK,
            plugin.messages().plain("gui.actions.tempmute"),
            plugin.messages().list("gui.actions.tempmute-lore")));
        inventory.setItem(KICK, Items.button(Material.LEATHER_BOOTS,
            plugin.messages().plain("gui.actions.kick"),
            plugin.messages().list("gui.actions.kick-lore")));
        inventory.setItem(WARN, Items.button(Material.YELLOW_BANNER,
            plugin.messages().plain("gui.actions.warn"),
            plugin.messages().list("gui.actions.warn-lore")));
        inventory.setItem(SHADOWMUTE_SLOT, Items.button(Material.INK_SAC,
            plugin.messages().plain(shadowMuted ? "gui.actions.unshadowmute" : "gui.actions.shadowmute"),
            plugin.messages().list("gui.actions.shadowmute-lore")));
        inventory.setItem(LOGS, Items.button(Material.WRITTEN_BOOK,
            plugin.messages().plain("gui.actions.chatlogs"),
            plugin.messages().list("gui.actions.chatlogs-lore")));
        inventory.setItem(TEMPLATES, Items.button(Material.WRITABLE_BOOK,
            plugin.messages().plain("gui.actions.templates"),
            plugin.messages().list("gui.actions.templates-lore")));
        if (lastIp != null) {
            inventory.setItem(IPBAN, Items.button(Material.IRON_BARS,
                plugin.messages().plain("gui.actions.ipban"),
                plugin.messages().list("gui.actions.ipban-lore")));
        }
        if (target.isOnline()) {
            boolean frozen = plugin.freeze().isFrozen(target.getUniqueId());
            inventory.setItem(FREEZE, Items.button(Material.ICE,
                plugin.messages().plain(frozen ? "gui.actions.unfreeze" : "gui.actions.freeze"),
                plugin.messages().list(frozen ? "gui.actions.unfreeze-lore" : "gui.actions.freeze-lore")));
            inventory.setItem(INVSEE, Items.button(Material.CHEST,
                plugin.messages().plain("gui.actions.invsee"),
                plugin.messages().list("gui.actions.invsee-lore")));
            inventory.setItem(ECHEST, Items.button(Material.ENDER_CHEST,
                plugin.messages().plain("gui.actions.echest"),
                plugin.messages().list("gui.actions.echest-lore")));
        }
        inventory.setItem(HISTORY, Items.button(Material.WRITABLE_BOOK,
            plugin.messages().plain("gui.actions.history"),
            plugin.messages().list("gui.actions.history-lore")));
        inventory.setItem(NOTES, Items.button(Material.BOOK,
            plugin.messages().plain("gui.actions.notes"),
            plugin.messages().list("gui.actions.notes-lore")));
        inventory.setItem(ALTS, Items.button(Material.PLAYER_HEAD,
            plugin.messages().plain("gui.actions.alts"),
            plugin.messages().list("gui.actions.alts-lore")));
        inventory.setItem(OPTOGGLE, Items.button(target.isOp() ? Material.NETHERITE_BLOCK : Material.NETHERITE_SCRAP,
            plugin.messages().plain(target.isOp() ? "gui.actions.deop" : "gui.actions.makeop"),
            plugin.messages().list("gui.actions.optoggle-lore")));
        inventory.setItem(BACK, Items.button(Material.OAK_DOOR,
            plugin.messages().plain("gui.actions.back"),
            plugin.messages().list("gui.actions.back-lore")));
        inventory.setItem(CLOSE, Items.button(Material.BARRIER,
            plugin.messages().plain("gui.actions.close"),
            plugin.messages().list("gui.actions.close-lore")));
        border();
        fillEmpty();
    }

    private String name() { return target.getName() == null ? "?" : target.getName(); }

    @Override
    public void onClick(InventoryClickEvent event) {
        event.setCancelled(true);
        Player mod = (Player) event.getWhoClicked();
        switch (event.getRawSlot()) {
            case BAN -> {
                if (banned) {
                    if (!plugin.staffPerms().canUse(mod, "sentinel.unban")) { mod.sendMessage(plugin.messages().prefixed("no-permission")); return; }
                    plugin.db().callbackOrError(mod, plugin.moderation().removeBan(mod.getUniqueId(), mod.getName(), target.getUniqueId(), name()),
                        ignored -> mod.closeInventory());
                } else {
                    if (!plugin.staffPerms().canPerform(mod, PunishmentType.BAN)) { mod.sendMessage(plugin.messages().prefixed("no-permission")); return; }
                    new ReasonGui(plugin, target, null, PunishmentType.BAN, 0).open(mod);
                }
            }
            case MUTE -> {
                if (muted) {
                    if (!plugin.staffPerms().canUse(mod, "sentinel.unmute")) { mod.sendMessage(plugin.messages().prefixed("no-permission")); return; }
                    plugin.db().callbackOrError(mod, plugin.moderation().removeMute(mod.getUniqueId(), mod.getName(), target.getUniqueId(), name()),
                        ignored -> mod.closeInventory());
                } else {
                    if (!plugin.staffPerms().canPerform(mod, PunishmentType.MUTE)) { mod.sendMessage(plugin.messages().prefixed("no-permission")); return; }
                    new ReasonGui(plugin, target, null, PunishmentType.MUTE, 0).open(mod);
                }
            }
            case SHADOWMUTE_SLOT -> {
                if (!plugin.staffPerms().canPerform(mod, PunishmentType.SHADOWMUTE)) { mod.sendMessage(plugin.messages().prefixed("no-permission")); return; }
                if (shadowMuted) {
                    plugin.db().callbackOrError(mod, plugin.moderation().removeShadowMute(mod.getUniqueId(), mod.getName(), target.getUniqueId(), name()),
                        ignored -> mod.closeInventory());
                } else new ReasonGui(plugin, target, null, PunishmentType.SHADOWMUTE, 0).open(mod);
            }
            case TEMPBAN -> {
                if (!plugin.staffPerms().canPerform(mod, PunishmentType.BAN)) { mod.sendMessage(plugin.messages().prefixed("no-permission")); return; }
                awaitDuration(mod, PunishmentType.BAN);
            }
            case TEMPMUTE -> {
                if (!plugin.staffPerms().canPerform(mod, PunishmentType.MUTE)) { mod.sendMessage(plugin.messages().prefixed("no-permission")); return; }
                awaitDuration(mod, PunishmentType.MUTE);
            }
            case KICK -> {
                if (!plugin.staffPerms().canPerform(mod, PunishmentType.KICK)) { mod.sendMessage(plugin.messages().prefixed("no-permission")); return; }
                new ReasonGui(plugin, target, null, PunishmentType.KICK, 0).open(mod);
            }
            case WARN -> {
                if (!plugin.staffPerms().canPerform(mod, PunishmentType.WARN)) { mod.sendMessage(plugin.messages().prefixed("no-permission")); return; }
                new ReasonGui(plugin, target, null, PunishmentType.WARN, 0).open(mod);
            }
            case IPBAN -> {
                if (!plugin.staffPerms().canPerform(mod, PunishmentType.IPBAN)) { mod.sendMessage(plugin.messages().prefixed("no-permission")); return; }
                if (lastIp != null) new ReasonGui(plugin, target, lastIp, PunishmentType.IPBAN, 0).open(mod);
                else mod.sendMessage(plugin.messages().prefixed("ipban-requires-online"));
            }
            case FREEZE -> {
                Player online = target.getPlayer();
                if (online != null) {
                    boolean nowFrozen = plugin.freeze().toggle(target.getUniqueId());
                    plugin.audit().record(mod.getName(), "FREEZE", name(), nowFrozen ? "frozen" : "unfrozen");
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
            case LOGS -> ChatLogGui.open(plugin, target, mod);
            case TEMPLATES -> new TemplatesGui(plugin, target).open(mod);
            case HISTORY -> HistoryGui.open(plugin, target, mod, 0);
            case NOTES -> NotesGui.open(plugin, target, mod);
            case ALTS -> AltsGui.open(plugin, target, mod);
            case OPTOGGLE -> {
                if (!plugin.staffPerms().canUse(mod, "sentinel.use")) { mod.sendMessage(plugin.messages().prefixed("no-permission")); return; }
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
            case BACK -> PlayersGui.open(plugin, 0, mod);
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
