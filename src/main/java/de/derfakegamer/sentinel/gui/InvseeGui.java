package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.util.Items;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.UUID;

/**
 * Live view/edit of a player's inventory including armor and off-hand.
 * Main storage + hotbar + armor + off-hand are editable; changes are written back
 * to the (online) target when the viewer closes the GUI. Label/separator slots are
 * decorative and click-protected.
 */
public final class InvseeGui extends Gui {
    private static final int ARMOR_LABEL = 45, OFFHAND_LABEL = 50;
    private static final int HELMET = 46, CHEST = 47, LEGS = 48, BOOTS = 49, OFFHAND = 51;

    private final UUID targetId;

    public InvseeGui(Sentinel plugin, Player target) {
        super(plugin);
        this.targetId = target.getUniqueId();
        this.inventory = Bukkit.createInventory(this, 54,
            plugin.messages().plain("gui-invsee-title", "player", target.getName()));
        PlayerInventory inv = target.getInventory();
        // main storage (target 9..35) -> gui 0..26
        for (int i = 9; i <= 35; i++) inventory.setItem(i - 9, inv.getItem(i));
        // hotbar (target 0..8) -> gui 27..35
        for (int i = 0; i <= 8; i++) inventory.setItem(27 + i, inv.getItem(i));
        // separator row
        for (int i = 36; i <= 44; i++) inventory.setItem(i, Items.filler());
        // armor + off-hand, set off in their own labelled area
        inventory.setItem(ARMOR_LABEL, Items.button(Material.ARMOR_STAND,
            plugin.messages().plain("gui.invsee.armor-label"),
            plugin.messages().list("gui.invsee.armor-lore")));
        inventory.setItem(HELMET, inv.getHelmet());
        inventory.setItem(CHEST, inv.getChestplate());
        inventory.setItem(LEGS, inv.getLeggings());
        inventory.setItem(BOOTS, inv.getBoots());
        inventory.setItem(OFFHAND_LABEL, Items.button(Material.SHIELD,
            plugin.messages().plain("gui.invsee.offhand-label"),
            plugin.messages().list("gui.invsee.offhand-lore")));
        ItemStack off = inv.getItemInOffHand();
        inventory.setItem(OFFHAND, (off == null || off.getType().isAir()) ? null : off);
        inventory.setItem(52, Items.filler());
        inventory.setItem(53, Items.filler());
    }

    private boolean isEditable(int slot) {
        return (slot >= 0 && slot <= 35) || slot == HELMET || slot == CHEST
            || slot == LEGS || slot == BOOTS || slot == OFFHAND;
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        int raw = event.getRawSlot();
        boolean inTop = raw < inventory.getSize();
        // Cancel only clicks on decorative label/separator slots; allow real item editing.
        if (inTop && !isEditable(raw)) event.setCancelled(true);
    }

    @Override
    public void onClose(InventoryCloseEvent event) {
        Player target = Bukkit.getPlayer(targetId);
        if (target == null) return; // target offline: cannot reconcile, drop edits
        PlayerInventory inv = target.getInventory();
        for (int i = 9; i <= 35; i++) inv.setItem(i, inventory.getItem(i - 9));
        for (int i = 0; i <= 8; i++) inv.setItem(i, inventory.getItem(27 + i));
        inv.setHelmet(inventory.getItem(HELMET));
        inv.setChestplate(inventory.getItem(CHEST));
        inv.setLeggings(inventory.getItem(LEGS));
        inv.setBoots(inventory.getItem(BOOTS));
        ItemStack off = inventory.getItem(OFFHAND);
        inv.setItemInOffHand(off == null ? new ItemStack(Material.AIR) : off);
        target.updateInventory();
    }
}
