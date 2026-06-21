package de.derfakegamer.sentinel.util;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class CompletionsTest {
    @Test void filterIsCaseInsensitivePrefix() {
        assertEquals(List.of("Alice", "alan"), Completions.filter("al", List.of("Alice", "Bob", "alan")));
    }
    @Test void nullOrEmptyPrefixReturnsAllSorted() {
        assertEquals(List.of("a", "b"), Completions.filter(null, List.of("b", "a")));
        assertEquals(List.of("a", "b"), Completions.filter("", List.of("b", "a")));
    }
    @Test void unknownPrefixIsEmpty() {
        assertTrue(Completions.of("zz", "1h", "1d").isEmpty());
    }
    @Test void durationsSuggested() {
        assertTrue(Completions.durations("1").containsAll(List.of("1h", "1d")));
        assertEquals(List.of("perm"), Completions.durations("p"));
    }
    @Test void tempDurationsExcludesPerm() {
        assertTrue(Completions.tempDurations("").containsAll(List.of("30m", "1h", "3d", "7d", "30d")));
        assertTrue(Completions.tempDurations("p").isEmpty(), "perm must not appear in temp durations");
    }
    @Test void tempDurationsFiltersCorrectly() {
        var result = Completions.tempDurations("3");
        assertTrue(result.contains("3d"), "3d must be in temp durations");
        assertFalse(result.contains("30d") && !result.contains("3d"), "3d and 30d both start with 3");
    }
    @Test void reasonsFiltered() {
        assertEquals(List.of("Spam"), Completions.reasons("sp", List.of("Spam", "Cheating")));
    }
}
