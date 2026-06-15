package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.PlayerRecord;
import de.derfakegamer.sentinel.util.Items;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
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
    private static final int BACK = 45, CLOSE = 53, BAN_ALL = 49;
    private static final DateTimeFormatter DATE =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC);

    private final OfflinePlayer target;
    private final List<PlayerRecord> alts;

    public AltsGui(Sentinel plugin, OfflinePlayer target) {
        super(plugin);
        this.target = target;
        this.alts = plugin.players().alts(target.getUniqueId());
        this.inventory = Bukkit.createInventory(this, 54,
            plugin.messages().plain("gui-alts-title", "player", name()));
        for (int i = 0; i < PAGE_SIZE && i < alts.size(); i++) {
            PlayerRecord r = alts.get(i);
            inventory.setItem(i, Items.head(Bukkit.getOfflinePlayer(r.uuid()),
                Component.text(r.name(), NamedTextColor.AQUA),
                List.of(grey("Shared IP: " + r.lastIp()),
                        grey("Last seen: " + DATE.format(Instant.ofEpochMilli(r.lastSeen()))),
                        grey("Click to open their actions"))));
        }
        if (alts.isEmpty())
            inventory.setItem(22, Items.button(Material.BARRIER,
                plugin.messages().plain("alts-empty"), List.of()));
        inventory.setItem(BAN_ALL, Items.button(Material.TNT,
            Component.text("Ban all alts", NamedTextColor.RED),
            List.of(grey(alts.size() + " accounts + the target"))));
        inventory.setItem(BACK, Items.button(Material.ARROW, Component.text("Back", NamedTextColor.GRAY), List.of()));
        inventory.setItem(CLOSE, Items.button(Material.BARRIER, Component.text("Close", NamedTextColor.RED), List.of()));
        fillEmpty();
    }

    private Component grey(String s) {
        return Component.text(s, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false);
    }

    private String name() { return target.getName() == null ? "?" : target.getName(); }

    @Override
    public void onClick(InventoryClickEvent event) {
        event.setCancelled(true);
        Player mod = (Player) event.getWhoClicked();
        int slot = event.getRawSlot();
        if (slot == BACK) { new PlayerActionsGui(plugin, target).open(mod); return; }
        if (slot == CLOSE) { mod.closeInventory(); return; }
        if (slot == BAN_ALL) {
            Player p = mod;
            new ConfirmGui(plugin, Component.text("Ban " + (alts.size() + 1) + " accounts?", NamedTextColor.RED), () -> {
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
            new PlayerActionsGui(plugin, alt).open(mod);
        }
    }
}
