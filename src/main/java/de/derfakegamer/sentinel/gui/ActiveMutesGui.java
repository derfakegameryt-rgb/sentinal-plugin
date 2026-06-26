package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.Punishment;
import de.derfakegamer.sentinel.model.PunishmentType;
import de.derfakegamer.sentinel.util.Items;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.util.List;

public final class ActiveMutesGui extends Gui {
    private static final int PAGE_SIZE = 45;

    private final int page;
    private final List<Punishment> mutes;

    /** Async opener: fetches active mutes then constructs and opens the GUI on the main thread. */
    public static void open(Sentinel plugin, Player viewer, int page) {
        plugin.db().callbackOrError(viewer, plugin.punishments().activeList(PunishmentType.MUTE, System.currentTimeMillis()),
            mutes -> new ActiveMutesGui(plugin, mutes != null ? mutes : List.of(), page).open(viewer));
    }

    public ActiveMutesGui(Sentinel plugin, List<Punishment> mutes, int page) {
        super(plugin);
        this.page = page;
        this.mutes = new java.util.ArrayList<>(mutes);
        this.mutes.removeIf(b -> plugin.owner().isOwner(b.targetUuid()));
        this.inventory = Bukkit.createInventory(this, 54, plugin.messages().plain("gui-mutes-title"));
        int from = page * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE && from + i < this.mutes.size(); i++) {
            Punishment b = this.mutes.get(from + i);
            String loreKey = b.isPermanent() ? "gui.mutes.entry-perm-lore" : "gui.mutes.entry-temp-lore";
            inventory.setItem(i, Items.head(Bukkit.getOfflinePlayer(b.targetUuid()),
                Component.text(b.targetName(), NamedTextColor.AQUA),
                plugin.messages().list(loreKey,
                    "reason", b.reason(),
                    "issuer", b.issuerName())));
        }
        navBar(page > 0, from + PAGE_SIZE < this.mutes.size(), true);
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        event.setCancelled(true);
        Player p = (Player) event.getWhoClicked();
        int slot = event.getRawSlot();
        if (slot == Gui.NAV_PREV) { open(plugin, p, page - 1); return; }
        if (slot == Gui.NAV_NEXT) { open(plugin, p, page + 1); return; }
        if (slot == Gui.NAV_BACK) { new AdminPanelGui(plugin).open(p); return; }
        if (slot == Gui.NAV_CLOSE) { p.closeInventory(); return; }
        int index = page * PAGE_SIZE + slot;
        if (slot >= 0 && slot < PAGE_SIZE && index < mutes.size())
            PlayerActionsGui.open(plugin, Bukkit.getOfflinePlayer(mutes.get(index).targetUuid()), p);
    }
}
