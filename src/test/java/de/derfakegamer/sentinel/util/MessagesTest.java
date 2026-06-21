package de.derfakegamer.sentinel.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class MessagesTest {
    private static String plain(Component c) { return PlainTextComponentSerializer.plainText().serialize(c); }

    @Test void listReturnsOneComponentPerLineWithPlaceholders() {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("gui.x.lore", List.of("<gray>Warns: <count>", "<red>Banned"));
        Messages m = new Messages(cfg);
        List<Component> lore = m.list("gui.x.lore", "count", "3");
        assertEquals(2, lore.size());
        assertEquals("Warns: 3", plain(lore.get(0)));
        assertEquals("Banned", plain(lore.get(1)));
    }

    @Test void listDisablesItalic() {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("gui.x.lore", List.of("hi"));
        assertEquals(net.kyori.adventure.text.format.TextDecoration.State.FALSE,
            new Messages(cfg).list("gui.x.lore").get(0).decoration(TextDecoration.ITALIC));
    }

    @Test void listMissingKeyIsEmpty() {
        assertTrue(new Messages(new YamlConfiguration()).list("nope").isEmpty());
    }

    @Test void plainStillWorks() {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("gui.x.name", "<red>Ban");
        assertEquals("Ban", plain(new Messages(cfg).plain("gui.x.name")));
    }
}
