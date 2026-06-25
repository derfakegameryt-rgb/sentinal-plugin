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
import org.bukkit.potion.PotionEffect;

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
            stripPotionParticles(staff);
            nowVanished = true;
        } else {
            vanished.remove(staff.getUniqueId());
            showToAll(staff);
            showEquipmentToOps(staff);
            restorePotionParticles(staff);
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
        UUID id = owner.getUniqueId();
        String shown = shownName(owner);
        boolean nowVanished;
        // toggleOwner only runs on the main thread (GUI click), but join events read the pair
        // (vanished, hideFromOps) on other threads. Order the writes so any interleaving over-hides
        // rather than under-hides: set the owner tier BEFORE marking vanished on enable, and clear
        // vanished BEFORE the tier on disable.
        if (!vanished.contains(id)) {
            hideFromOps.add(id);
            vanished.add(id);
            hideFromAll(owner);
            broadcastExcept(owner, JoinQuitListener.nameMessage("multiplayer.player.left", shown));
            nowVanished = true;
        } else {
            vanished.remove(id);
            hideFromOps.remove(id);
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
        sendEquipToOps(staff, true);
    }

    /** Restore a previously-vanished staff member's real armour + hands for op viewers. */
    private void showEquipmentToOps(Player staff) {
        sendEquipToOps(staff, false);
    }

    private void sendEquipToOps(Player staff, boolean hide) {
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (other.isOp() && !other.equals(staff)) {
                Player viewer = other;
                // Mutating another player's view must run on that viewer's region thread (Folia).
                plugin.scheduler().runForEntity(viewer, () -> sendEquip(viewer, staff, hide));
            }
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

    /** Suppress a vanished staff member's potion-effect particles (the one player-attached particle
     *  source the API can control); duration/amplifier are preserved, only the swirl is hidden. */
    public void stripPotionParticles(Player staff) {
        if (hideFromOps.contains(staff.getUniqueId())) return;   // owner-tier is fully hidden anyway
        plugin.scheduler().runForEntity(staff, () -> {
            for (PotionEffect e : new java.util.ArrayList<>(staff.getActivePotionEffects())) {
                if (e.hasParticles()) reapply(staff, e, false);
            }
        });
    }

    /** Restore particles on a previously-vanished staff member's active potion effects. */
    private void restorePotionParticles(Player staff) {
        plugin.scheduler().runForEntity(staff, () -> {
            for (PotionEffect e : new java.util.ArrayList<>(staff.getActivePotionEffects())) {
                if (!e.hasParticles()) reapply(staff, e, true);
            }
        });
    }

    private void reapply(Player staff, PotionEffect e, boolean particles) {
        try {
            // Remove first so the particles flag is replaced regardless of addPotionEffect's overwrite rules.
            staff.removePotionEffect(e.getType());
            staff.addPotionEffect(new PotionEffect(
                e.getType(), e.getDuration(), e.getAmplifier(), e.isAmbient(), particles, e.hasIcon()));
        } catch (Throwable t) {
            plugin.debug("vanish potion: " + t.getMessage());
        }
    }
}
