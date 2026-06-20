package de.derfakegamer.sentinel.discord;

import de.derfakegamer.sentinel.model.PunishmentType;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.junit.jupiter.api.Assertions.*;

class WebhookDiscordServiceTest {
    // capture posts by overriding post(), which is called only after the blank-URL guard in send()
    static class Capturing extends WebhookDiscordService {
        final List<String> posts = new CopyOnWriteArrayList<>();
        Capturing(String url) { super(url); }
        @Override protected void post(String body) { posts.add(body); }
    }

    @Test void logsPunishmentAsOneLine() {
        Capturing s = new Capturing("https://discord.com/api/webhooks/x/y");
        s.logPunishment(PunishmentType.BAN, "Bob", "Mod", "spam", 0L);
        assertEquals(1, s.posts.size());
        assertTrue(s.posts.get(0).contains("Bob"));
        assertTrue(s.posts.get(0).contains("Mod"));
        assertTrue(s.posts.get(0).contains("spam"));
    }

    @Test void logsReport() {
        Capturing s = new Capturing("https://discord.com/api/webhooks/x/y");
        s.logReport("Alice", "Bob", "cheating");
        assertEquals(1, s.posts.size());
        assertTrue(s.posts.get(0).contains("Alice") && s.posts.get(0).contains("Bob") && s.posts.get(0).contains("cheating"));
    }

    @Test void blankUrlSendsNothing() {
        // blank-URL service: send()'s guard must fire before post() is ever called
        Capturing blank = new Capturing("");
        blank.logReport("a", "b", "c");
        assertEquals(0, blank.posts.size(), "blank URL must not reach post()");

        // non-blank-URL service: post() must be called exactly once
        Capturing real = new Capturing("https://discord.com/api/webhooks/x/y");
        real.logReport("a", "b", "c");
        assertEquals(1, real.posts.size(), "non-blank URL must reach post() exactly once");
    }

    @Test void presenceAndShutdownAreNoops() {
        WebhookDiscordService s = new WebhookDiscordService("");
        assertDoesNotThrow(() -> { s.updatePresence(1, 2); s.shutdown(); });
    }
}
