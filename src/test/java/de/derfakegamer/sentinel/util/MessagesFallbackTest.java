package de.derfakegamer.sentinel.util;

import de.derfakegamer.sentinel.manager.SecretMessages;
import net.kyori.adventure.text.Component;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FIX 2 — a malformed MiniMessage template must never throw out of a command/listener:
 * the deserialize helper falls back to plain text.
 */
class MessagesFallbackTest {

    @Test void malformedTemplateFallsBackInsteadOfThrowing() {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("prefix", "");
        // unclosed / invalid tag — MiniMessage would normally throw a ParsingException
        cfg.set("broken", "<click:bogus>oops</bad");
        Messages messages = new Messages(cfg);

        Component out = assertDoesNotThrow(() -> messages.plain("broken"));
        assertNotNull(out);
    }

    @Test void malformedTemplateWithPlaceholderFallsBack() {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("prefix", "");
        cfg.set("broken", "<click:bogus><name></bad");
        Messages messages = new Messages(cfg);

        Component out = assertDoesNotThrow(() -> messages.prefixed("broken", "name", "Steve"));
        assertNotNull(out);
    }

    @Test void secretMessagesUnknownKeyReturnsKeyAsText() {
        SecretMessages sm = new SecretMessages("");
        Component out = assertDoesNotThrow(() -> sm.plain("unknown-key-fallback"));
        assertNotNull(out);
    }
}
