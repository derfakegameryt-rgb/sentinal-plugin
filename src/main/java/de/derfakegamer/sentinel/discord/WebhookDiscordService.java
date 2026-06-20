package de.derfakegamer.sentinel.discord;

import de.derfakegamer.sentinel.model.PunishmentType;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class WebhookDiscordService implements DiscordService {
    private final String url;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public WebhookDiscordService(String url) { this.url = url == null ? "" : url; }

    @Override public void logPunishment(PunishmentType type, String targetName, String issuerName, String reason, long expiresAt) {
        String verb = switch (type) {
            case BAN, IPBAN -> "banned"; case MUTE -> "muted"; case SHADOWMUTE -> "shadow-muted";
            case WARN -> "warned"; case KICK -> "kicked";
        };
        send("**" + targetName + "** was " + verb + " by " + issuerName
            + (reason == null || reason.isBlank() ? "" : ": " + reason));
    }
    @Override public void logReport(String reporterName, String targetName, String reason) {
        send(":triangular_flag_on_post: **" + reporterName + "** reported **" + targetName + "**: " + reason);
    }
    @Override public void logAppeal(String targetName, PunishmentType type, String text) {
        send(":envelope: **" + targetName + "** appealed their " + type.name().toLowerCase() + ": " + text);
    }
    @Override public void updatePresence(int online, int max) { /* webhook has no presence */ }
    @Override public void shutdown() { /* nothing to close */ }

    /** Posts a plain-text line to the webhook, async; no-op if URL unset. Overridable for tests. */
    protected void send(String content) {
        if (url.isBlank()) return;
        String body = "{\"content\":\"" + escape(content) + "\"}";
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            http.sendAsync(req, HttpResponse.BodyHandlers.discarding());
        } catch (Exception ignored) { /* fail-soft */ }
    }

    public static String escape(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> { if (c < 0x20) b.append(String.format("\\u%04x", (int) c)); else b.append(c); }
            }
        }
        return b.toString();
    }
}
