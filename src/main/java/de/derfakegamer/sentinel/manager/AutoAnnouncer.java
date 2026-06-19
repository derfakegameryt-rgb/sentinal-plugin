package de.derfakegamer.sentinel.manager;

import de.derfakegamer.sentinel.Sentinel;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;

import java.util.List;

public final class AutoAnnouncer {
    private final Sentinel plugin;
    private int index = 0;

    public AutoAnnouncer(Sentinel plugin) { this.plugin = plugin; }

    public void start() {
        if (!plugin.getConfig().getBoolean("announcements.enabled", true)) return;
        long ticks = Math.max(20, plugin.getConfig().getLong("announcements.interval-seconds", 300)) * 20L;
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::announceNext, ticks, ticks);
    }

    /** Broadcasts the next configured message (round-robin). Returns the raw line, or null if none. */
    public String announceNext() {
        List<String> messages = plugin.getConfig().getStringList("announcements.messages");
        if (messages.isEmpty()) return null;
        String prefix = plugin.getConfig().getString("announcements.prefix", "");
        String line = messages.get(index % messages.size());
        index++;
        Bukkit.broadcast(MiniMessage.miniMessage().deserialize(prefix + line));
        return line;
    }
}
