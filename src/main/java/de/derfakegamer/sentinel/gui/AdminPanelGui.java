package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.util.Items;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;

public final class AdminPanelGui extends Gui {
    // Row 1 (general): operators, whitelist
    private static final int OPS = 10, WHITELIST = 11;
    // Row 2 (moderation): bans, mutes, reports, appeals, audit, announcements toggle
    private static final int BANS = 19, MUTES = 20, REPORTS = 21, APPEALS = 22, AUDIT = 23, ANNOUNCE = 24;
    // Row 3 (player tools): player manager, vanish, staff chat, self name/skin/reset
    private static final int PLAYERS = 28, VANISH = 29, STAFFCHAT = 30, SETNAME = 31, SETSKIN = 32, RESETPROFILE = 33;
    private static final int CLOSE = 49;

    public AdminPanelGui(Sentinel plugin) {
        super(plugin);
        this.inventory = Bukkit.createInventory(this, 54, plugin.messages().plain("gui-panel-title"));
        inventory.setItem(OPS,       button(Material.PLAYER_HEAD,   "gui.panel.ops",            "gui.panel.ops-lore"));
        inventory.setItem(WHITELIST, button(Material.NAME_TAG,      "gui.panel.whitelist",      "gui.panel.whitelist-lore"));
        inventory.setItem(BANS,      button(Material.IRON_BARS,     "gui.panel.bans",           "gui.panel.bans-lore"));
        inventory.setItem(MUTES,     button(Material.BOOK,          "gui.panel.mutes",          "gui.panel.mutes-lore"));
        inventory.setItem(REPORTS,   button(Material.PAPER,         "gui.panel.reports",        "gui.panel.reports-lore"));
        inventory.setItem(APPEALS,   button(Material.WRITABLE_BOOK, "gui.panel.appeals",        "gui.panel.appeals-lore"));
        inventory.setItem(AUDIT,     button(Material.WRITABLE_BOOK, "gui.panel.audit",          "gui.panel.audit-lore"));
        inventory.setItem(ANNOUNCE,  announceItem());
        inventory.setItem(PLAYERS,   button(Material.PLAYER_HEAD,   "gui.panel.player-manager", "gui.panel.player-manager-lore"));
        inventory.setItem(VANISH,    button(Material.ENDER_EYE,     "gui.panel.vanish",         "gui.panel.vanish-lore"));
        inventory.setItem(STAFFCHAT, button(Material.NETHER_STAR,   "gui.panel.staffchat",      "gui.panel.staffchat-lore"));
        inventory.setItem(SETNAME,      button(Material.NAME_TAG,     "gui.panel.setname",      "gui.panel.setname-lore"));
        inventory.setItem(SETSKIN,      button(Material.PLAYER_HEAD,  "gui.panel.setskin",      "gui.panel.setskin-lore"));
        inventory.setItem(RESETPROFILE, button(Material.WATER_BUCKET, "gui.panel.resetprofile", "gui.panel.resetprofile-lore"));
        inventory.setItem(CLOSE,     Items.button(Material.BARRIER, plugin.messages().plain("gui.panel.close"), java.util.List.of()));
        border();
        fillEmpty();
    }

    private org.bukkit.inventory.ItemStack button(Material m, String nameKey, String loreKey) {
        return Items.button(m, plugin.messages().plain(nameKey), plugin.messages().list(loreKey));
    }

    /** Toggle button for the recurring auto-announcements; reflects the current on/off state. */
    private org.bukkit.inventory.ItemStack announceItem() {
        boolean on = plugin.announcer().isEnabled();
        return Items.button(Material.BELL,
            plugin.messages().plain(on ? "gui.panel.announce-on" : "gui.panel.announce-off"),
            plugin.messages().list("gui.panel.announce-lore"));
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        event.setCancelled(true);
        Player p = (Player) event.getWhoClicked();
        switch (event.getRawSlot()) {
            case OPS -> new OperatorsGui(plugin, 0).open(p);
            case BANS -> ActiveBansGui.open(plugin, p, 0);
            case MUTES -> ActiveMutesGui.open(plugin, p, 0);
            case REPORTS -> ReportsGui.open(plugin, 0, p);
            case WHITELIST -> new WhitelistGui(plugin, 0).open(p);
            case APPEALS -> AppealsGui.open(plugin, p, 0);
            case AUDIT -> AuditGui.open(plugin, p, 0);
            case ANNOUNCE -> {
                boolean on = !plugin.announcer().isEnabled();
                plugin.announcer().setEnabled(on);
                inventory.setItem(ANNOUNCE, announceItem());
            }
            case PLAYERS -> PlayersGui.open(plugin, 0, p);
            case VANISH -> {
                boolean v = plugin.vanish().toggle(p);
                p.sendMessage(plugin.messages().prefixed(v ? "vanish-on" : "vanish-off"));
                plugin.audit().record(p.getName(), "VANISH", p.getName(), v ? "on" : "off");
            }
            case STAFFCHAT -> {
                boolean on = plugin.staffChat().toggle(p.getUniqueId());
                p.sendMessage(plugin.messages().prefixed(on ? "staffchat-on" : "staffchat-off"));
                plugin.audit().record(p.getName(), "STAFFCHAT", p.getName(), on ? "on" : "off");
            }
            case SETNAME -> {
                if (!plugin.staffPerms().canUse(p, "sentinel.profile")) { p.sendMessage(plugin.messages().prefixed("no-permission")); return; }
                p.closeInventory();
                p.sendMessage(plugin.messages().prefixed("profile-enter-name"));
                plugin.chatInput().await(p.getUniqueId(), input -> {
                    if (!de.derfakegamer.sentinel.manager.ProfileManager.isValidName(input)) {
                        p.sendMessage(plugin.messages().prefixed("profile-bad-name")); return;
                    }
                    plugin.profile().setName(p, input, p.getName());
                    p.sendMessage(plugin.messages().prefixed("profile-name-set", "player", p.getName(), "name", input));
                });
            }
            case SETSKIN -> {
                if (!plugin.staffPerms().canUse(p, "sentinel.profile")) { p.sendMessage(plugin.messages().prefixed("no-permission")); return; }
                p.closeInventory();
                p.sendMessage(plugin.messages().prefixed("profile-enter-skin"));
                plugin.chatInput().await(p.getUniqueId(), input ->
                    plugin.profile().setSkin(p, input, p.getName(), ok ->
                        p.sendMessage(plugin.messages().prefixed(
                            ok ? "profile-skin-set" : "profile-skin-not-found", "player", p.getName(), "name", input))));
            }
            case RESETPROFILE -> {
                if (!plugin.staffPerms().canUse(p, "sentinel.profile")) { p.sendMessage(plugin.messages().prefixed("no-permission")); return; }
                plugin.profile().reset(p, p.getName());
                p.sendMessage(plugin.messages().prefixed("profile-reset", "player", p.getName()));
                p.closeInventory();
            }
            case CLOSE -> p.closeInventory();
        }
    }
}
