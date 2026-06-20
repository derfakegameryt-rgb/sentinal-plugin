package de.derfakegamer.sentinel.discord;

import de.derfakegamer.sentinel.model.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class AuditStatsEmbedsTest {
    @Test void statsEmbedListsActorsAndActions() {
        EmbedData e = AuditStatsEmbeds.stats(List.of(new ActorCount("Mod", 5)), List.of(new ActionCount("BAN", 3)));
        assertTrue(e.fields().stream().anyMatch(f -> f.value().contains("Mod") && f.value().contains("5")));
        assertTrue(e.fields().stream().anyMatch(f -> f.value().contains("BAN") && f.value().contains("3")));
    }
    @Test void auditEmbedListsEntries() {
        EmbedData e = AuditStatsEmbeds.audit("Bob", List.of(new AuditEntry(1, "Mod", "BAN", "Bob", "spam", 1000)));
        assertTrue(e.title().contains("Bob"));
        assertTrue(e.fields().stream().anyMatch(f -> f.value().contains("BAN")));
    }
    @Test void emptyStatsDoesNotCrash() {
        assertNotNull(AuditStatsEmbeds.stats(List.of(), List.of()));
        assertNotNull(AuditStatsEmbeds.audit("X", List.of()));
    }
}
