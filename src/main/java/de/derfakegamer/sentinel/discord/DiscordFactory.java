package de.derfakegamer.sentinel.discord;

import de.derfakegamer.sentinel.Sentinel;
import org.bukkit.configuration.file.FileConfiguration;

public final class DiscordFactory {
    private DiscordFactory() {}

    public static DiscordService create(Sentinel plugin) {
        FileConfiguration cfg = plugin.getConfig();
        boolean botEnabled = cfg.getBoolean("discord.bot.enabled", false);
        String token = cfg.getString("discord.bot.token", "");
        if (botEnabled && token != null && !token.isBlank()) {
            return new BotDiscordService(plugin);
        }
        String webhook = cfg.getString("discord.webhook-url", "");
        if (webhook != null && !webhook.isBlank()) return new WebhookDiscordService(webhook);
        return new NoopDiscordService();
    }
}
