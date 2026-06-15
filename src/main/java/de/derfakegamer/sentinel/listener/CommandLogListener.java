package de.derfakegamer.sentinel.listener;

import de.derfakegamer.sentinel.Sentinel;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.util.List;

public final class CommandLogListener implements Listener {
    private final Sentinel plugin;
    public CommandLogListener(Sentinel plugin) { this.plugin = plugin; }

    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent event) {
        // Never log credential-bearing commands (auth plugins) in plaintext.
        if (isSensitive(event.getMessage())) return;
        plugin.chatLog().logCommand(event.getPlayer().getUniqueId(), event.getPlayer().getName(), event.getMessage());
    }

    private boolean isSensitive(String message) {
        String cmd = message.toLowerCase().split("\\s+")[0];
        if (cmd.startsWith("/")) cmd = cmd.substring(1);
        // strip a plugin namespace like "minecraft:login"
        int colon = cmd.indexOf(':');
        if (colon >= 0) cmd = cmd.substring(colon + 1);
        List<String> ignored = plugin.getConfig().getStringList("logging.ignore-commands");
        for (String s : ignored) if (cmd.equals(s.toLowerCase())) return true;
        return false;
    }
}
