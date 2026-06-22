package de.derfakegamer.sentinel.manager;

import de.derfakegamer.sentinel.Sentinel;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;

import java.util.List;

public final class AutoAnnouncer {
    private final Sentinel plugin;
    private int index = 0;
    private org.bukkit.scheduler.BukkitTask task;

    public AutoAnnouncer(Sentinel plugin) { this.plugin = plugin; }

    public void start() {
        if (isEnabled()) schedule();
    }

    /** Whether the recurring announcements are on (persisted in config). */
    public boolean isEnabled() {
        return plugin.getConfig().getBoolean("announcements.enabled", true);
    }

    /** Turns the recurring announcements on/off at runtime and persists the choice. */
    public void setEnabled(boolean on) {
        plugin.getConfig().set("announcements.enabled", on);
        plugin.saveConfig();
        if (on) schedule(); else stop();
    }

    private void schedule() {
        if (task != null) return;   // already running
        long ticks = Math.max(20, plugin.getConfig().getLong("announcements.interval-seconds", 300)) * 20L;
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::announceNext, ticks, ticks);
    }

    /** Cancels the running announcement timer, if any. */
    public void stop() {
        if (task != null) { task.cancel(); task = null; }
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
