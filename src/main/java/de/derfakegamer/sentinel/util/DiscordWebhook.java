package de.derfakegamer.sentinel.util;

import de.derfakegamer.sentinel.Sentinel;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public final class DiscordWebhook {
    private final Sentinel plugin;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public DiscordWebhook(Sentinel plugin) { this.plugin = plugin; }

    /** Posts a plain-text line to the configured webhook, async; no-op if unset. */
    public void post(String content) {
        String url = plugin.getConfig().getString("discord.webhook-url", "");
        if (url == null || url.isBlank()) return;
        String body = "{\"content\":\"" + escape(content) + "\"}";
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(15))
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
                http.send(req, HttpResponse.BodyHandlers.discarding());
            } catch (Exception e) {
                plugin.getLogger().fine("Discord webhook failed: " + e.getMessage());
            }
        });
    }

    /** Escapes a string for embedding in a JSON string literal. */
    static String escape(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c)); // other control chars
                    else b.append(c);
                }
            }
        }
        return b.toString();
    }
}
