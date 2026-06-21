package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.AuditEntry;
import de.derfakegamer.sentinel.util.Items;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.util.ArrayList;
import java.util.List;

public final class AuditGui extends Gui {
    private static final int PAGE_SIZE = 45;
    private final List<AuditEntry> entries;
    private final int page;

    public static void open(Sentinel plugin, Player viewer, int page) {
        int p = Math.max(0, page);
        plugin.db().callbackOrError(viewer, plugin.audit().recent(PAGE_SIZE, p * PAGE_SIZE),
            list -> new AuditGui(plugin, list == null ? List.of() : list, p).open(viewer));
    }

    public AuditGui(Sentinel plugin, List<AuditEntry> entries, int page) {
        super(plugin);
        this.entries = entries;
        this.page = page;
        this.inventory = Bukkit.createInventory(this, 54, plugin.messages().plain("gui-audit-title"));
        for (int i = 0; i < entries.size() && i < PAGE_SIZE; i++) {
            AuditEntry e = entries.get(i);
            String details = e.details() == null || e.details().isBlank() ? "—" : e.details();
            List<Component> lore = new ArrayList<>();
            lore.addAll(plugin.messages().list("gui.audit.by-lore", "actor", e.actor()));
            lore.addAll(plugin.messages().list("gui.audit.details-lore", "details", details));
            lore.addAll(plugin.messages().list("gui.audit.time-lore",
                "ago", de.derfakegamer.sentinel.util.TimeFormat.ago(e.createdAt())));
            inventory.setItem(i, Items.button(Material.PAPER,
                Component.text(e.action() + (e.target() == null ? "" : " · " + e.target()), NamedTextColor.AQUA),
                lore));
        }
        navBar(page > 0, entries.size() == PAGE_SIZE, true);
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        event.setCancelled(true);
        Player p = (Player) event.getWhoClicked();
        switch (event.getRawSlot()) {
            case Gui.NAV_PREV -> AuditGui.open(plugin, p, page - 1);
            case Gui.NAV_NEXT -> AuditGui.open(plugin, p, page + 1);
            case Gui.NAV_BACK -> new AdminPanelGui(plugin).open(p);
            case Gui.NAV_CLOSE -> p.closeInventory();
        }
    }
}
