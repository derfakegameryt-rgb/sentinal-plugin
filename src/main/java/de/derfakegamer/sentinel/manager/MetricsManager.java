package de.derfakegamer.sentinel.manager;

import de.derfakegamer.sentinel.Sentinel;
import org.bstats.bukkit.Metrics;
import org.bstats.charts.SimplePie;

/**
 * Optional, privacy-respecting usage metrics via bStats.
 *
 * bStats is opt-out per server (plugins/bStats/config.yml) and only reports coarse,
 * non-identifying data. Starting it never affects gameplay; any failure is swallowed.
 *
 * NOTE: {@link #BSTATS_PLUGIN_ID} is a sentinel (-1) until the plugin is registered at
 * https://bstats.org/getting-started — register it, then replace the constant with the
 * numeric id bStats assigns. Until then metrics simply stay off.
 */
public final class MetricsManager {
    private static final int BSTATS_PLUGIN_ID = -1; // TODO: replace with the id from bstats.org

    private final Sentinel plugin;

    public MetricsManager(Sentinel plugin) { this.plugin = plugin; }

    /** Starts metrics if a real bStats id is configured. Never throws. */
    public void start() {
        if (BSTATS_PLUGIN_ID <= 0) {
            plugin.getLogger().fine("bStats metrics disabled: no plugin id registered yet.");
            return;
        }
        try {
            Metrics metrics = new Metrics(plugin, BSTATS_PLUGIN_ID);
            metrics.addCustomChart(new SimplePie("storage_type", () -> "SQLite"));
            metrics.addCustomChart(new SimplePie("discord_webhook",
                () -> plugin.getConfig().getBoolean("discord.enabled", false) ? "enabled" : "disabled"));
            metrics.addCustomChart(new SimplePie("chat_moderation",
                () -> plugin.getConfig().getBoolean("chat.anti-spam.enabled", true) ? "enabled" : "disabled"));
        } catch (Throwable t) {
            plugin.getLogger().fine("bStats metrics failed to start: " + t.getMessage());
        }
    }
}
