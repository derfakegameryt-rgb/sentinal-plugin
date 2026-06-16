package de.derfakegamer.sentinel.manager;

import de.derfakegamer.sentinel.Sentinel;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ChatModerationTest {
    ServerMock server; Sentinel plugin;

    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    private ChatModeration fresh() { return new ChatModeration(plugin); }

    @Test void cleanMessageIsAllowed() {
        assertEquals(ChatModeration.Action.ALLOW, fresh().evaluate(UUID.randomUUID(), "hello there", 1000).action());
    }

    @Test void repeatedMessageIsBlocked() {
        ChatModeration cm = fresh();
        UUID id = UUID.randomUUID();
        assertEquals(ChatModeration.Action.ALLOW, cm.evaluate(id, "spam", 1000).action());
        assertEquals(ChatModeration.Action.ALLOW, cm.evaluate(id, "spam", 2000).action());
        assertEquals(ChatModeration.Action.BLOCK, cm.evaluate(id, "spam", 3000).action()); // 3rd repeat
    }

    @Test void advertisingIsBlocked() {
        assertEquals(ChatModeration.Action.BLOCK,
            fresh().evaluate(UUID.randomUUID(), "join play.hypixel.net now", 1000).action());
    }

    @Test void slowmodeBlocksFastSecondMessage() {
        plugin.getConfig().set("chat.slowmode-seconds", 5);
        ChatModeration cm = fresh();
        UUID id = UUID.randomUUID();
        assertEquals(ChatModeration.Action.ALLOW, cm.evaluate(id, "a", 1000).action());
        assertEquals(ChatModeration.Action.BLOCK, cm.evaluate(id, "b", 2000).action()); // < 5s later
        assertEquals(ChatModeration.Action.ALLOW, cm.evaluate(id, "c", 7000).action());  // > 5s later
    }

    @Test void wordFilterCensors() {
        plugin.getConfig().set("chat.word-filter.mode", "censor");
        plugin.getConfig().set("chat.word-filter.words", java.util.List.of("badword"));
        ChatModeration cm = fresh();
        var out = cm.evaluate(UUID.randomUUID(), "you are a badword", 1000);
        assertEquals(ChatModeration.Action.CENSOR, out.action());
        assertFalse(out.censored().contains("badword"));
    }

    @Test void ordinaryDottedWordsAreNotFlaggedAsAds() {
        ChatModeration cm = fresh();
        assertEquals(ChatModeration.Action.ALLOW, cm.evaluate(UUID.randomUUID(), "good.bye everyone", 1000).action());
        assertEquals(ChatModeration.Action.ALLOW, cm.evaluate(UUID.randomUUID(), "open file.txt please", 1000).action());
        assertEquals(ChatModeration.Action.ALLOW, cm.evaluate(UUID.randomUUID(), "e.g. 1.5 is fine", 1000).action());
    }

    @Test void allCapsIsBlockedOrCensored() {
        ChatModeration cm = fresh();
        ChatModeration.Outcome o = cm.evaluate(java.util.UUID.randomUUID(), "STOP DOING THAT RIGHT NOW", 1000);
        assertNotEquals(ChatModeration.Action.ALLOW, o.action());
    }

    @Test void shortShoutIsAllowed() {
        assertEquals(ChatModeration.Action.ALLOW, fresh().evaluate(java.util.UUID.randomUUID(), "OK!", 1000).action());
    }

    @Test void characterFloodIsBlocked() {
        assertEquals(ChatModeration.Action.BLOCK,
            fresh().evaluate(java.util.UUID.randomUUID(), "heyyyyyyyyyy", 1000).action());
    }

    @Test void obfuscatedBadWordIsCaught() {
        plugin.getConfig().set("chat.word-filter.enabled", true);
        plugin.getConfig().set("chat.word-filter.mode", "block");
        plugin.getConfig().set("chat.word-filter.words", java.util.List.of("badword"));
        ChatModeration cm = fresh();
        assertEquals(ChatModeration.Action.BLOCK,
            cm.evaluate(java.util.UUID.randomUUID(), "b.a.d.w.o.r.d", 1000).action());
    }

    @Test void normalizeHelperCollapsesObfuscation() {
        assertEquals("badword", ChatModeration.normalize("B.A.D.W.0.R.D"));
        assertEquals("fuck", ChatModeration.normalize("fuuuuck"));
    }
}
