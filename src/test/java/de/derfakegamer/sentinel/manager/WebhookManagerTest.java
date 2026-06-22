package de.derfakegamer.sentinel.manager;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.derfakegamer.sentinel.model.Punishment;
import de.derfakegamer.sentinel.model.PunishmentType;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WebhookManagerTest {

    private static JsonObject firstEmbed(String json) {
        return JsonParser.parseString(json).getAsJsonObject()
            .getAsJsonArray("embeds").get(0).getAsJsonObject();
    }

    private static String fieldValue(JsonObject embed, String name) {
        for (var el : embed.getAsJsonArray("fields")) {
            JsonObject f = el.getAsJsonObject();
            if (f.get("name").getAsString().equals(name)) return f.get("value").getAsString();
        }
        return null;
    }

    private static Punishment punishment(PunishmentType type, long expiresAt) {
        return Punishment.builder().type(type).targetUuid(UUID.randomUUID())
            .targetName("Bob").reason("Cheating").issuerUuid(UUID.randomUUID())
            .issuerName("Mod").createdAt(1000L).expiresAt(expiresAt).active(true).build();
    }

    @Test
    void permanentBanPayloadHasTitlePlayerStaffReasonAndDuration() {
        String json = WebhookManager.punishmentPayload(punishment(PunishmentType.BAN, 0), 1000L, "Sentinel");
        JsonObject embed = firstEmbed(json);
        assertEquals("Player banned", embed.get("title").getAsString());
        assertEquals("Bob", fieldValue(embed, "Player"));
        assertEquals("Mod", fieldValue(embed, "Staff"));
        assertEquals("Cheating", fieldValue(embed, "Reason"));
        assertEquals("permanent", fieldValue(embed, "Duration"));
    }

    @Test
    void tempMutePayloadShowsRemainingDuration() {
        long now = 1000L;
        String json = WebhookManager.punishmentPayload(punishment(PunishmentType.MUTE, now + 60_000L), now, "Sentinel");
        JsonObject embed = firstEmbed(json);
        assertEquals("Player muted", embed.get("title").getAsString());
        assertEquals("1m", fieldValue(embed, "Duration"));
    }

    @Test
    void kickAndWarnOmitDurationField() {
        JsonObject kick = firstEmbed(WebhookManager.punishmentPayload(punishment(PunishmentType.KICK, 0), 1000L, "S"));
        JsonObject warn = firstEmbed(WebhookManager.punishmentPayload(punishment(PunishmentType.WARN, 0), 1000L, "S"));
        assertNull(fieldValue(kick, "Duration"));
        assertNull(fieldValue(warn, "Duration"));
    }

    @Test
    void nullReasonRendersAsNa() {
        Punishment p = Punishment.builder().type(PunishmentType.WARN).targetUuid(UUID.randomUUID())
            .targetName("Bob").issuerName("Mod").createdAt(1L).expiresAt(0).active(true).build();
        JsonObject embed = firstEmbed(WebhookManager.punishmentPayload(p, 1L, "Sentinel"));
        assertEquals("N/A", fieldValue(embed, "Reason"));
    }

    @Test
    void usernameIsCarriedIntoEnvelope() {
        String json = WebhookManager.punishmentPayload(punishment(PunishmentType.BAN, 0), 1000L, "MyBot");
        assertEquals("MyBot", JsonParser.parseString(json).getAsJsonObject().get("username").getAsString());
    }

    @Test
    void reportPayloadHasReporterTargetAndReason() {
        JsonObject embed = firstEmbed(WebhookManager.reportPayload("Alice", "Bob", "Griefing", "Sentinel"));
        assertEquals("New report", embed.get("title").getAsString());
        assertEquals("Bob", fieldValue(embed, "Reported"));
        assertEquals("Alice", fieldValue(embed, "By"));
        assertEquals("Griefing", fieldValue(embed, "Reason"));
    }
}
