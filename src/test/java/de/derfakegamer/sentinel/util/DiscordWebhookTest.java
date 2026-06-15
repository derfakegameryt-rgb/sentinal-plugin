package de.derfakegamer.sentinel.util;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DiscordWebhookTest {
    @Test void escapesQuotesAndNewlines() {
        assertEquals("a\\\"b", DiscordWebhook.escape("a\"b"));
        assertEquals("line1\\nline2", DiscordWebhook.escape("line1\nline2"));
        assertEquals("c:\\\\path", DiscordWebhook.escape("c:\\path"));
    }

    @Test void escapesRawControlChars() {
        char bell = (char) 7, nul = (char) 0;
        assertEquals("a\\u0000b", DiscordWebhook.escape("a" + nul + "b"));
        assertEquals("x\\u0007y", DiscordWebhook.escape("x" + bell + "y"));
    }
}
