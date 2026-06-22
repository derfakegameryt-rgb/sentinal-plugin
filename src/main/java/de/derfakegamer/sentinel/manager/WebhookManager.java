package de.derfakegamer.sentinel.manager;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.Punishment;
import de.derfakegamer.sentinel.model.PunishmentType;
import de.derfakegamer.sentinel.util.TimeFormat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.logging.Level;

/**
 * Posts moderation events to a Discord channel via an incoming webhook URL.
 *
 * Deliberately uses only the JDK HTTP client and Gson (already on the classpath) — no JDA,
 * no bot token, nothing that would grow the jar. Sending is fire-and-forget and fully async;
 * a misconfigured or unreachable webhook never blocks or spams the console.
 *
 * Payload building ({@link #punishmentPayload} / {@link #reportPayload}) is static and pure so
 * it can be unit-tested without a network.
 */
public final class WebhookManager {
    private static final String DEFAULT_USERNAME = "Sentinel";

    // Discord embed accent colours (decimal RGB), chosen per event severity.
    private static final int COLOR_BAN = 0xE53935;     // red
    private static final int COLOR_MUTE = 0xFB8C00;    // orange
    private static final int COLOR_WARN = 0xFDD835;    // yellow
    private static final int COLOR_KICK = 0x8E24AA;    // purple
    private static final int COLOR_REPORT = 0x3B82F6;  // blue

    private final Sentinel plugin;
    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    public WebhookManager(Sentinel plugin) { this.plugin = plugin; }

    private boolean enabled() { return plugin.getConfig().getBoolean("discord.enabled", false); }

    private String url() { return plugin.getConfig().getString("discord.webhook-url", "").trim(); }

    private String username() {
        String u = plugin.getConfig().getString("discord.username", DEFAULT_USERNAME);
        return (u == null || u.isBlank()) ? DEFAULT_USERNAME : u;
    }

    /** Fire-and-forget Discord notification for a newly-issued punishment. Safe to call off-thread. */
    public void notifyPunishment(Punishment p) {
        if (!enabled() || url().isEmpty() || p == null) return;
        post(punishmentPayload(p, System.currentTimeMillis(), username()));
    }

    /** Fire-and-forget Discord notification for a new player report. */
    public void notifyReport(String reporter, String target, String reason) {
        if (!enabled() || url().isEmpty()) return;
        post(reportPayload(reporter, target, reason, username()));
    }

    static String punishmentPayload(Punishment p, long now, String username) {
        String verb = switch (p.type()) {
            case BAN, IPBAN -> "banned";
            case MUTE -> "muted";
            case SHADOWMUTE -> "shadow-muted";
            case WARN -> "warned";
            case KICK -> "kicked";
        };
        JsonObject embed = new JsonObject();
        embed.addProperty("title", "Player " + verb);
        embed.addProperty("color", colorFor(p.type()));
        embed.addProperty("timestamp", Instant.ofEpochMilli(now).toString());

        JsonArray fields = new JsonArray();
        fields.add(field("Player", orNa(p.targetName()), true));
        fields.add(field("Staff", orNa(p.issuerName()), true));
        if (p.type() != PunishmentType.KICK && p.type() != PunishmentType.WARN)
            fields.add(field("Duration", p.isPermanent() ? "permanent" : TimeFormat.until(p.expiresAt(), now), true));
        fields.add(field("Reason", orNa(p.reason()), false));
        embed.add("fields", fields);

        return envelope(username, embed);
    }

    static String reportPayload(String reporter, String target, String reason, String username) {
        JsonObject embed = new JsonObject();
        embed.addProperty("title", "New report");
        embed.addProperty("color", COLOR_REPORT);
        embed.addProperty("timestamp", Instant.now().toString());
        JsonArray fields = new JsonArray();
        fields.add(field("Reported", orNa(target), true));
        fields.add(field("By", orNa(reporter), true));
        fields.add(field("Reason", orNa(reason), false));
        embed.add("fields", fields);
        return envelope(username, embed);
    }

    private static String envelope(String username, JsonObject embed) {
        JsonObject root = new JsonObject();
        root.addProperty("username", username);
        JsonArray embeds = new JsonArray();
        embeds.add(embed);
        root.add("embeds", embeds);
        return root.toString();
    }

    private static JsonObject field(String name, String value, boolean inline) {
        JsonObject o = new JsonObject();
        o.addProperty("name", name);
        o.addProperty("value", value);
        o.addProperty("inline", inline);
        return o;
    }

    private static int colorFor(PunishmentType type) {
        return switch (type) {
            case BAN, IPBAN -> COLOR_BAN;
            case MUTE, SHADOWMUTE -> COLOR_MUTE;
            case WARN -> COLOR_WARN;
            case KICK -> COLOR_KICK;
        };
    }

    private static String orNa(String s) { return (s == null || s.isBlank()) ? "N/A" : s; }

    private void post(String json) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url()))
            .header("Content-Type", "application/json")
            .header("User-Agent", "Sentinel")
            .timeout(Duration.ofSeconds(15))
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build();
        http.sendAsync(req, HttpResponse.BodyHandlers.discarding())
            .exceptionally(ex -> {
                // A broken webhook must never spam the console: keep it at FINE (visible only
                // with debug logging), never WARNING/SEVERE.
                plugin.getLogger().log(Level.FINE, "Discord webhook post failed: " + ex.getMessage());
                return null;
            });
    }
}
