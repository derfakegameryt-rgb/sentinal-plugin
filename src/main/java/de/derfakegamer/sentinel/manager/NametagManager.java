package de.derfakegamer.sentinel.manager;

import de.derfakegamer.sentinel.Sentinel;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Renders a staff-set custom name ABOVE THE HEAD using a {@link TextDisplay} mounted as a passenger of
 * the player (so it follows automatically, no per-tick task). The vanilla name above the head — which is
 * bound to the GameProfile name at login and cannot be changed mid-session — is suppressed by adding the
 * player to a scoreboard team whose name-tag visibility is NEVER. This is the only Paper-API way to swap
 * the above-head name live (no packet library).
 *
 * <p>One entry point, {@link #refresh(Player)}: it shows the floating name iff the player has a display-name
 * override AND is not vanished (a vanished — especially owner-tier — player must never have a floating name
 * betraying them), and hides it otherwise. Display entities are non-persistent so a crash can't orphan them.
 */
public final class NametagManager {
    private static final String TEAM = "sentinel_nick"; // shared no-nametag team (name-tag visibility NEVER)

    private final Sentinel plugin;
    private final float heightOffset; // translation up from the passenger mount point, tunable via config
    // player UUID -> the floating TextDisplay's UUID, so we can find + remove it later.
    private final ConcurrentHashMap<UUID, UUID> displays = new ConcurrentHashMap<>();
    // player UUID -> the team it was on BEFORE we moved it to the no-nametag team, so a prefix/TAB
    // plugin's team membership is restored on reset (a player can be on only one team per scoreboard).
    private final ConcurrentHashMap<UUID, String> priorTeam = new ConcurrentHashMap<>();

    public NametagManager(Sentinel plugin) {
        this.plugin = plugin;
        this.heightOffset = (float) plugin.getConfig().getDouble("nametag-height", 0.3);
    }

    /**
     * Single source of truth: show the floating name iff the player has an override and is not vanished,
     * otherwise remove it. Safe to call any number of times (it updates in place / no-ops as needed).
     */
    public void refresh(Player player) {
        String name = plugin.profile().overrideJoinName(player.getUniqueId());
        if (name != null && !plugin.vanish().isVanished(player.getUniqueId())) show(player, name);
        else hide(player);
    }

    /** Removes a player's floating name and team entry — called on quit (entity cleanup). */
    public void handleQuit(Player player) {
        UUID id = player.getUniqueId();
        String name = player.getName();
        removeDisplay(id); // best-effort entity removal (guarded; setPersistent(false) is the backstop)
        priorTeam.remove(id); // don't restore the prior team on quit — the player is gone
        plugin.scheduler().runGlobal(() -> { // scoreboard is global state (Folia: global region)
            try {
                Team team = team(false);
                if (team != null) team.removeEntry(name);
            } catch (Throwable ignored) { }
        });
    }

    /** Removes every floating name (called on plugin disable, best-effort and synchronous). */
    public void disableAll() {
        for (UUID displayId : displays.values()) {
            try {
                Entity e = Bukkit.getEntity(displayId);
                if (e != null) e.remove();
            } catch (Throwable ignored) {
                // best-effort on shutdown
            }
        }
        displays.clear();
        Team team = team(false);
        if (team != null) try { team.unregister(); } catch (Throwable ignored) { }
    }

    private void show(Player player, String name) {
        // The shared scoreboard is global state (Folia: global region); the display entity lives on the
        // player's region. Split the two so each runs on the right thread.
        plugin.scheduler().runGlobal(() -> {
            try { hideVanillaNametag(player); } catch (Throwable ignored) { }
        });
        plugin.scheduler().runForEntity(player, () -> {
            try {
                // Re-check on the entity thread: state may have flipped since refresh() was called
                // (e.g. setName immediately followed by vanish). Never mount a name on a now-vanished
                // player or one whose override was cleared — that is the secrecy guarantee.
                if (plugin.profile().overrideJoinName(player.getUniqueId()) == null
                    || plugin.vanish().isVanished(player.getUniqueId())) {
                    removeDisplay(player.getUniqueId());
                    return;
                }
                Component text = ProfileManager.renderName(name); // MiniMessage colour support
                TextDisplay existing = display(player.getUniqueId());
                // Reuse only if it is STILL mounted; a death/respawn/world-change detaches it, leaving a
                // stale entity that must be cleared and respawned rather than silently updated in place.
                if (existing != null && player.getPassengers().contains(existing)) { existing.text(text); return; }
                if (existing != null) removeDisplay(player.getUniqueId());
                TextDisplay td = player.getWorld().spawn(player.getLocation(), TextDisplay.class, d -> {
                    d.text(text);
                    d.setBillboard(Display.Billboard.CENTER);          // always faces the viewer
                    d.setPersistent(false);                            // never saved to disk -> no orphans
                    d.setVisibleByDefault(true);
                    d.setTransformation(new Transformation(
                        new Vector3f(0f, heightOffset, 0f), new AxisAngle4f(),
                        new Vector3f(1f, 1f, 1f), new AxisAngle4f()));
                });
                player.addPassenger(td); // ride the player so it follows with no per-tick work
                displays.put(player.getUniqueId(), td.getUniqueId());
            } catch (Throwable t) {
                plugin.getLogger().fine("nametag show failed for " + player.getName() + ": " + t.getMessage());
            }
        });
    }

    private void hide(Player player) {
        plugin.scheduler().runForEntity(player, () -> removeDisplay(player.getUniqueId()));
        plugin.scheduler().runGlobal(() -> {
            try { showVanillaNametag(player); } catch (Throwable ignored) { }
        });
    }

    private void removeDisplay(UUID playerId) {
        UUID displayId = displays.remove(playerId);
        if (displayId == null) return;
        try {
            Entity e = Bukkit.getEntity(displayId);
            if (e != null) e.remove();
        } catch (Throwable ignored) { }
    }

    private TextDisplay display(UUID playerId) {
        UUID displayId = displays.get(playerId);
        if (displayId == null) return null;
        Entity e = Bukkit.getEntity(displayId);
        return e instanceof TextDisplay td ? td : null;
    }

    // ---- vanilla name-tag suppression via a shared scoreboard team ----

    private void hideVanillaNametag(Player player) {
        org.bukkit.scoreboard.Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
        String name = player.getName(); // the scoreboard entry = real account name
        // Remember the team the player was already on (a prefix/TAB plugin's), so reset can restore it.
        Team current = null;
        try { current = board.getEntryTeam(name); } catch (Throwable ignored) { }
        if (current != null && !TEAM.equals(current.getName())) priorTeam.put(player.getUniqueId(), current.getName());
        team(true).addEntry(name);
    }

    private void showVanillaNametag(Player player) {
        org.bukkit.scoreboard.Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
        String name = player.getName();
        Team team = board.getTeam(TEAM);
        if (team != null) team.removeEntry(name);
        // Restore the team the player was on before we hijacked their nametag, if it still exists.
        String prev = priorTeam.remove(player.getUniqueId());
        if (prev != null) {
            Team t = board.getTeam(prev);
            if (t != null) t.addEntry(name);
        }
    }

    /** The shared no-name-tag team; created on first use when {@code create} is true, else null if absent. */
    private Team team(boolean create) {
        Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
        Team team = board.getTeam(TEAM);
        if (team == null && create) {
            team = board.registerNewTeam(TEAM);
            team.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER);
        }
        return team;
    }
}
