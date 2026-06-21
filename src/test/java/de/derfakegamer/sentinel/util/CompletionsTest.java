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
    @Test void reasonsFiltered() {
        assertEquals(List.of("Spam"), Completions.reasons("sp", List.of("Spam", "Cheating")));
    }
}
