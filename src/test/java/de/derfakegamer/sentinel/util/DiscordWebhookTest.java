package de.derfakegamer.sentinel.util;

import de.derfakegamer.sentinel.discord.WebhookDiscordService;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Escape-logic tests — delegated to WebhookDiscordService.escape after DiscordWebhook was removed. */
class DiscordWebhookTest {
    @Test void escapesQuotesAndNewlines() {
        assertEquals("a\\\"b", WebhookDiscordService.escape("a\"b"));
        assertEquals("line1\\nline2", WebhookDiscordService.escape("line1\nline2"));
        assertEquals("c:\\\\path", WebhookDiscordService.escape("c:\\path"));
    }

    @Test void escapesRawControlChars() {
        char bell = (char) 7, nul = (char) 0;
        assertEquals("a\\u0000b", WebhookDiscordService.escape("a" + nul + "b"));
        assertEquals("x\\u0007y", WebhookDiscordService.escape("x" + bell + "y"));
    }
}
