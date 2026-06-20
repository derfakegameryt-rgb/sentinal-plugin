package de.derfakegamer.sentinel.discord;

import de.derfakegamer.sentinel.model.PunishmentType;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class DiscordLogicTest {
    @Test void punishmentEmbedHasFieldsAndColor() {
        EmbedData e = DiscordEmbeds.punishment(PunishmentType.BAN, "Bob", "Mod", "spam", 0L);
        assertTrue(e.title().toLowerCase().contains("ban"));
        assertTrue(e.fields().stream().anyMatch(f -> f.value().contains("Bob")));
        assertTrue(e.fields().stream().anyMatch(f -> f.value().contains("Mod")));
        assertTrue(e.fields().stream().anyMatch(f -> f.value().contains("spam")));
        assertTrue(e.color() != 0);
    }

    @Test void banAndWarnHaveDifferentColors() {
        assertNotEquals(
            DiscordEmbeds.punishment(PunishmentType.BAN, "a", "b", "c", 0L).color(),
            DiscordEmbeds.punishment(PunishmentType.WARN, "a", "b", "c", 0L).color());
    }

    @Test void reportAndAppealEmbeds() {
        assertTrue(DiscordEmbeds.report("Al", "Bo", "x").fields().stream().anyMatch(f -> f.value().contains("Al")));
        assertTrue(DiscordEmbeds.appeal("Bo", PunishmentType.MUTE, "please").fields().stream().anyMatch(f -> f.value().contains("please")));
    }

    @Test void mayModerateRequiresAtLeastOneStaffRole() {
        assertTrue(SlashAuth.mayModerate(Set.of("1", "2"), List.of("2", "3")));
        assertFalse(SlashAuth.mayModerate(Set.of("1"), List.of("2", "3")));
        assertFalse(SlashAuth.mayModerate(Set.of("1"), List.of()));
        assertFalse(SlashAuth.mayModerate(Set.of(), List.of("2")));
    }

    @Test void statusFormatterSubstitutes() {
        assertEquals("12/100 online", StatusFormatter.format("{online}/{max} online", 12, 100));
        assertEquals("plain", StatusFormatter.format("plain", 1, 2));
    }
}
