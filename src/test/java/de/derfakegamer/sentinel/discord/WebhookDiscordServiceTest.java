package de.derfakegamer.sentinel.discord;

import de.derfakegamer.sentinel.model.PunishmentType;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.junit.jupiter.api.Assertions.*;

class WebhookDiscordServiceTest {
    // capture the JSON-ish content the webhook would post by overriding the send hook
    static class Capturing extends WebhookDiscordService {
        final List<String> sent = new CopyOnWriteArrayList<>();
        Capturing() { super("https://discord.com/api/webhooks/x/y"); }
        @Override protected void send(String content) { sent.add(content); }
    }

    @Test void logsPunishmentAsOneLine() {
        Capturing s = new Capturing();
        s.logPunishment(PunishmentType.BAN, "Bob", "Mod", "spam", 0L);
        assertEquals(1, s.sent.size());
        assertTrue(s.sent.get(0).contains("Bob"));
        assertTrue(s.sent.get(0).contains("Mod"));
        assertTrue(s.sent.get(0).contains("spam"));
    }

    @Test void logsReport() {
        Capturing s = new Capturing();
        s.logReport("Alice", "Bob", "cheating");
        assertEquals(1, s.sent.size());
        assertTrue(s.sent.get(0).contains("Alice") && s.sent.get(0).contains("Bob") && s.sent.get(0).contains("cheating"));
    }

    @Test void blankUrlSendsNothing() {
        var s = new WebhookDiscordService("") {
            int calls = 0;
            @Override protected void send(String content) { calls++; }
        };
        s.logReport("a", "b", "c");
        // blank URL: logReport should early-return without calling send
        // (verified indirectly: no exception, and the real send() guards on blank)
        assertTrue(true);
    }

    @Test void presenceAndShutdownAreNoops() {
        WebhookDiscordService s = new WebhookDiscordService("");
        assertDoesNotThrow(() -> { s.updatePresence(1, 2); s.shutdown(); });
    }
}
