package de.derfakegamer.sentinel.util;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * Guards the bundled German translation against drift: every translated key must be a real
 * key (no typos), and every player-facing (top-level string) key must be translated. Keys that
 * are intentionally left to fall back to English (the nested gui.* labels) are not required.
 */
class MessagesLanguageTest {

    private static YamlConfiguration bundled(String name) {
        var in = MessagesLanguageTest.class.getResourceAsStream("/" + name);
        assertNotNull(in, name + " should be on the classpath");
        return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
    }

    @Test
    void germanKeysAllExistInEnglish() {
        YamlConfiguration en = bundled("messages.yml");
        YamlConfiguration de = bundled("messages_de.yml");
        for (String key : de.getKeys(true)) {
            if (de.isConfigurationSection(key)) continue;
            assertTrue(en.contains(key), "messages_de.yml has key '" + key + "' that does not exist in messages.yml");
        }
    }

    @Test
    void everyTopLevelPlayerFacingKeyIsTranslated() {
        YamlConfiguration en = bundled("messages.yml");
        YamlConfiguration de = bundled("messages_de.yml");
        for (String key : en.getKeys(false)) {
            if (!en.isString(key)) continue; // skip the nested gui section (falls back to English)
            assertTrue(de.isString(key), "messages_de.yml is missing a translation for '" + key + "'");
        }
    }

    @Test
    void germanMessagesRenderWithoutThrowing() {
        Messages messages = new Messages(bundled("messages_de.yml"));
        assertDoesNotThrow(() -> messages.prefixed("banned", "player", "Bob", "reason", "Cheating"));
        assertDoesNotThrow(() -> messages.plain("import-done", "imported", "3", "skipped", "1"));
    }
}
