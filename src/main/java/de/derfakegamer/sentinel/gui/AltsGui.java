package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.PlayerRecord;
import de.derfakegamer.sentinel.util.Items;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

public final class AltsGui extends Gui {
    private static final int PAGE_SIZE = 45;
    private static final DateTimeFormatter DATE =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC);

    private final OfflinePlayer target;
    private final List<PlayerRecord> alts;

    /**
     * Asynchronously fetches alt accounts for {@code target} then constructs and opens the GUI on
     * the main thread. Use this instead of {@code new AltsGui(...).open(viewer)}.
     */
    public static void open(Sentinel plugin, OfflinePlayer target, Player viewer) {
        plugin.db().callback(plugin.players().alts(target.getUniqueId()),
            alts -> new AltsGui(plugin, target, alts).open(viewer));
    }

    /**
     * Constructs the GUI with pre-fetched alt list. Call {@link #open(Sentinel, OfflinePlayer, Player)}
     * from the main thread instead of this constructor.
     */
    public AltsGui(Sentinel plugin, OfflinePlayer target, List<PlayerRecord> alts) {
        super(plugin);
        this.target = target;
        this.alts = alts;
        this.inventory = Bukkit.createInventory(this, 54,
            plugin.messages().plain("gui-alts-title", "player", name()));
        for (int i = 0; i < PAGE_SIZE && i < alts.size(); i++) {
            PlayerRecord r = alts.get(i);
            inventory.setItem(i, Items.head(Bukkit.getOfflinePlayer(r.uuid()),
                Component.text(r.name(), NamedTextColor.AQUA),
                plugin.messages().list("gui.alts.entry-lore",
                    "ip", r.lastIp(),
                    "date", DATE.format(Instant.ofEpochMilli(r.lastSeen())))));
        }
        if (alts.isEmpty())
            inventory.setItem(22, Items.button(Material.BARRIER,
                plugin.messages().plain("alts-empty"), List.of()));
        navBar(false, false, true);
        inventory.setItem(NAV_ACT_R2, Items.button(Material.TNT,
            plugin.messages().plain("gui.alts.ban-all"),
            plugin.messages().list("gui.alts.ban-all-lore", "count", String.valueOf(alts.size()))));
    }

    private String name() { return target.getName() == null ? "?" : target.getName(); }

    @Override
    public void onClick(InventoryClickEvent event) {
        event.setCancelled(true);
        Player mod = (Player) event.getWhoClicked();
        int slot = event.getRawSlot();
        if (slot == NAV_BACK) { PlayerActionsGui.open(plugin, target, mod); return; }
        if (slot == NAV_CLOSE) { mod.closeInventory(); return; }
        if (slot == NAV_ACT_R2) {
            if (!plugin.staffPerms().canPerform(mod, de.derfakegamer.sentinel.model.PunishmentType.BAN)) {
                mod.sendMessage(plugin.messages().prefixed("no-permission")); return;
            }
            Player p = mod;
            new ConfirmGui(plugin, Component.text("Ban " + (alts.size() + 1) + " accounts?", NamedTextColor.RED), () -> {
                // Fire-and-forget: futures complete on the DB thread; results are broadcast by ModerationService
                plugin.moderation().apply(p.getUniqueId(), p.getName(), target.getUniqueId(),
                    target.getName() == null ? "?" : target.getName(), null,
                    de.derfakegamer.sentinel.model.PunishmentType.BAN, 0, "Alt of a banned account");
                for (PlayerRecord r : alts)
                    plugin.moderation().apply(p.getUniqueId(), p.getName(), r.uuid(), r.name(), null,
                        de.derfakegamer.sentinel.model.PunishmentType.BAN, 0, "Alt of a banned account");
            }, null).open(p);
            return;
        }
        if (slot >= 0 && slot < PAGE_SIZE && slot < alts.size()) {
            OfflinePlayer alt = Bukkit.getOfflinePlayer(alts.get(slot).uuid());
            PlayerActionsGui.open(plugin, alt, mod);
        }
    }
}
