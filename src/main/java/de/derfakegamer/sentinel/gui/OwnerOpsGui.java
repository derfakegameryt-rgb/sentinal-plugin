package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.AuditEntry;
import de.derfakegamer.sentinel.util.Items;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Hidden owner-only inspector: every operator, online state, and how they got op. Op grants done
 * through Sentinel's admin panel are attributed (actor + when); anyone whose latest op-action is not a
 * panel grant is flagged "external" — the red flag for op acquired via console / vanilla {@code /op}.
 * All text hard-coded; no audit, no trace.
 */
public final class OwnerOpsGui extends Gui {
    private static final int CAP = 45;
    private static final int BACK = 48, CLOSE = 50;

    /** Loads the recent audit once (off-thread), then builds and opens the GUI on the main thread. */
    public static void open(Sentinel plugin, Player viewer) {
        List<OfflinePlayer> ops = new ArrayList<>(Bukkit.getOperators());
        ops.sort(java.util.Comparator.comparing(
            o -> o.getName() != null ? o.getName() : o.getUniqueId().toString(),
            String.CASE_INSENSITIVE_ORDER));
        plugin.db().callbackOrError(viewer, plugin.audit().recent(200, 0),
            entries -> new OwnerOpsGui(plugin, ops, entries).open(viewer));
    }

    OwnerOpsGui(Sentinel plugin, List<OfflinePlayer> ops, List<AuditEntry> entries) {
        super(plugin);
        this.inventory = Bukkit.createInventory(this, 54, Component.text("Op Inspector", NamedTextColor.DARK_AQUA));

        // Newest-first scan: keep the most recent op-action (OP/DEOP) per target name.
        Map<String, AuditEntry> latest = new HashMap<>();
        if (entries != null) {
            for (AuditEntry e : entries) {
                if (e.target() == null) continue;
                if (("OP".equals(e.action()) || "DEOP".equals(e.action())) && !latest.containsKey(e.target()))
                    latest.put(e.target(), e);
            }
        }

        java.util.UUID owner = plugin.owner().uuid();
        long now = System.currentTimeMillis();
        for (int i = 0; i < CAP && i < ops.size(); i++) {
            OfflinePlayer op = ops.get(i);
            String name = op.getName() != null ? op.getName() : op.getUniqueId().toString();
            List<Component> lore = new ArrayList<>();
            lore.add(op.isOnline()
                ? Component.text("Online", NamedTextColor.GREEN)
                : Component.text("Offline", NamedTextColor.GRAY));
            if (op.getUniqueId().equals(owner)) {
                lore.add(Component.text("This is you", NamedTextColor.AQUA));
            } else {
                AuditEntry e = latest.get(name);
                if (e != null && "OP".equals(e.action())) {
                    lore.add(Component.text("Opped by " + e.actor() + " via panel", NamedTextColor.GREEN));
                    lore.add(Component.text(ago(now - e.createdAt()) + " ago", NamedTextColor.DARK_GRAY));
                } else {
                    lore.add(Component.text("External — console / /op", NamedTextColor.RED));
                    lore.add(Component.text("not granted through the panel", NamedTextColor.DARK_GRAY));
                }
            }
            inventory.setItem(i, Items.head(op, Component.text(name, NamedTextColor.YELLOW), lore));
        }
        if (ops.isEmpty()) {
            inventory.setItem(22, Items.button(Material.PAPER,
                Component.text("No operators", NamedTextColor.GRAY), List.of()));
        }
        inventory.setItem(BACK, Items.button(Material.OAK_DOOR, Component.text("Back", NamedTextColor.YELLOW), List.of()));
        inventory.setItem(CLOSE, Items.button(Material.BARRIER, Component.text("Close", NamedTextColor.RED), List.of()));
        border();
        fillEmpty();
    }

    private static String ago(long ms) {
        long s = ms / 1000;
        if (s < 60) return s + "s";
        long m = s / 60;
        if (m < 60) return m + "m";
        long h = m / 60;
        if (h < 24) return h + "h";
        return (h / 24) + "d";
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        event.setCancelled(true);
        Player p = (Player) event.getWhoClicked();
        if (!plugin.owner().isOwner(p)) { p.closeInventory(); return; }   // owner-only, defense in depth
        switch (event.getRawSlot()) {
            case BACK -> new OwnerPanelGui(plugin).open(p);
            case CLOSE -> p.closeInventory();
            default -> { /* op heads are inert */ }
        }
    }
}
