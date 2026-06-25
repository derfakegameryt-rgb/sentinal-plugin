package de.derfakegamer.sentinel.manager;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.listener.JoinQuitListener;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class VanishManager {
    private final Sentinel plugin;
    /** Everyone currently hidden (admin- or owner-tier). Drives {@link #isVanished} and GUI filtering. */
    private final Set<UUID> vanished = ConcurrentHashMap.newKeySet();
    /** Owner-tier subset: hidden from EVERYONE, including ops. */
    private final Set<UUID> hideFromOps = ConcurrentHashMap.newKeySet();

    public VanishManager(Sentinel plugin) { this.plugin = plugin; }

    public boolean isVanished(UUID player) { return vanished.contains(player); }

    /** True for owner-tier vanish (hidden from everyone, including ops). */
    public boolean isHiddenFromAll(UUID player) { return hideFromOps.contains(player); }

    /** Flips admin vanish (hide from non-ops) and updates visibility. Returns new state (true = now vanished). */
    public boolean toggle(Player staff) {
        boolean nowVanished;
        if (vanished.add(staff.getUniqueId())) {
            hideFromNonOps(staff);
            hideEquipmentFromOps(staff);
            nowVanished = true;
        } else {
            vanished.remove(staff.getUniqueId());
            showToAll(staff);
            showEquipmentToOps(staff);
            nowVanished = false;
        }
        return nowVanished;
    }

    /**
     * Flips owner-tier vanish: hidden from EVERYONE. On enable broadcasts a fake "left the game" message,
     * on disable a fake "joined the game" message — using the owner's display-name override if set, so an
     * undercover owner's real account name never leaks. Returns new state (true = now vanished).
     */
    public boolean toggleOwner(Player owner) {
        String shown = shownName(owner);
        boolean nowVanished;
        if (vanished.add(owner.getUniqueId())) {
            hideFromOps.add(owner.getUniqueId());
            hideFromAll(owner);
            broadcastExcept(owner, JoinQuitListener.nameMessage("multiplayer.player.left", shown));
            nowVanished = true;
        } else {
            hideFromOps.remove(owner.getUniqueId());
            vanished.remove(owner.getUniqueId());
            showToAll(owner);
            broadcastExcept(owner, JoinQuitListener.nameMessage("multiplayer.player.joined", shown));
            nowVanished = false;
        }
        return nowVanished;
    }

    /** When a player joins, hide every currently-vanished player from them as their tier requires. */
    public void applyOnJoin(Player joiner) {
        for (UUID id : vanished) {
            Player staff = Bukkit.getPlayer(id);
            if (staff == null || staff.equals(joiner)) continue;
            if (hideFromOps.contains(id) || !joiner.isOp()) {
                joiner.hidePlayer(plugin, staff);            // hidden from this joiner
            } else {
                sendEquip(joiner, staff, true);              // op joiner still sees an admin-vanished staff: blank gear
            }
        }
        // If the joiner is themselves vanished (e.g. a relog), re-apply their own hiding.
        UUID self = joiner.getUniqueId();
        if (isVanished(self)) {
            if (hideFromOps.contains(self)) hideFromAll(joiner);
            else { hideFromNonOps(joiner); hideEquipmentFromOps(joiner); }
        }
    }

    private String shownName(Player owner) {
        String override = plugin.profile().overrideJoinName(owner.getUniqueId());
        return override != null ? override : owner.getName();
    }

    private void broadcastExcept(Player except, Component message) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!p.equals(except)) p.sendMessage(message);
        }
    }

    private void hideFromNonOps(Player staff) {
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (!other.isOp() && !other.equals(staff)) {
                Player viewer = other;
                plugin.scheduler().runForEntity(viewer, () -> viewer.hidePlayer(plugin, staff));
            }
        }
    }

    private void hideFromAll(Player staff) {
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (!other.equals(staff)) {
                Player viewer = other;
                plugin.scheduler().runForEntity(viewer, () -> viewer.hidePlayer(plugin, staff));
            }
        }
    }

    private void showToAll(Player staff) {
        for (Player other : Bukkit.getOnlinePlayers()) {
            Player viewer = other;
            plugin.scheduler().runForEntity(viewer, () -> viewer.showPlayer(plugin, staff));
        }
    }

    /** Blank a vanished staff member's armour + hands for the ops who can still see them. */
    public void hideEquipmentFromOps(Player staff) {
        if (hideFromOps.contains(staff.getUniqueId())) return;   // owner-tier is fully hidden; nothing renders
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer.isOp() && !viewer.equals(staff)) sendEquip(viewer, staff, true);
        }
    }

    /** Restore a previously-vanished staff member's real armour + hands for op viewers. */
    private void showEquipmentToOps(Player staff) {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer.isOp() && !viewer.equals(staff)) sendEquip(viewer, staff, false);
        }
    }

    private void sendEquip(Player viewer, Player staff, boolean hide) {
        try {
            PlayerInventory inv = staff.getInventory();
            ItemStack air = new ItemStack(Material.AIR);
            viewer.sendEquipmentChange(staff, EquipmentSlot.HAND,     hide ? air : inv.getItemInMainHand());
            viewer.sendEquipmentChange(staff, EquipmentSlot.OFF_HAND, hide ? air : inv.getItemInOffHand());
            viewer.sendEquipmentChange(staff, EquipmentSlot.HEAD,     hide ? air : orAir(inv.getHelmet(), air));
            viewer.sendEquipmentChange(staff, EquipmentSlot.CHEST,    hide ? air : orAir(inv.getChestplate(), air));
            viewer.sendEquipmentChange(staff, EquipmentSlot.LEGS,     hide ? air : orAir(inv.getLeggings(), air));
            viewer.sendEquipmentChange(staff, EquipmentSlot.FEET,     hide ? air : orAir(inv.getBoots(), air));
        } catch (Throwable t) {
            plugin.debug("vanish equip: " + t.getMessage());
        }
    }

    private static ItemStack orAir(ItemStack item, ItemStack air) { return item != null ? item : air; }
}
