package de.derfakegamer.sentinel.hook;

import de.derfakegamer.sentinel.Sentinel;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

/**
 * PlaceholderAPI expansion exposing Sentinel state as %sentinel_<key>% placeholders.
 *
 * Deliberately limited to in-memory, synchronous state — PlaceholderAPI resolves on the main
 * thread and must never block on a database read. Punishment-count placeholders are intentionally
 * omitted for that reason (they would require async DB access).
 *
 * Keys (player-scoped unless noted):
 *   vanished, frozen, afk, staffchat  -> "true" / "false"
 *   maintenance (server-wide)         -> "true" / "false"
 */
public final class SentinelExpansion extends PlaceholderExpansion {
    private final Sentinel plugin;

    public SentinelExpansion(Sentinel plugin) { this.plugin = plugin; }

    @Override
    public @NotNull String getIdentifier() { return "sentinel"; }

    @Override
    public @NotNull String getAuthor() { return "DerFakeGamer"; }

    @Override
    public @NotNull String getVersion() { return plugin.getPluginMeta().getVersion(); }

    /** Keep the expansion registered across PlaceholderAPI reloads. */
    @Override
    public boolean persist() { return true; }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        switch (params.toLowerCase()) {
            case "maintenance":
                return bool(plugin.maintenance().isEnabled());
            case "vanished":
                return player == null ? "" : bool(plugin.vanish().isVanished(player.getUniqueId()));
            case "frozen":
                return player == null ? "" : bool(plugin.freeze().isFrozen(player.getUniqueId()));
            case "afk":
                return player == null ? "" : bool(plugin.afk().isAfk(player.getUniqueId()));
            case "staffchat":
                return player == null ? "" : bool(plugin.staffChat().isToggled(player.getUniqueId()));
            default:
                return null; // unknown placeholder -> PlaceholderAPI leaves it untouched
        }
    }

    private static String bool(boolean b) { return b ? "true" : "false"; }
}
