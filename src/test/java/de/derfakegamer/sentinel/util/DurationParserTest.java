package de.derfakegamer.sentinel.util;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DurationParserTest {
    @Test void parsesSeconds() { assertEquals(30_000L, DurationParser.parse("30s")); }
    @Test void parsesMinutes() { assertEquals(10 * 60_000L, DurationParser.parse("10m")); }
    @Test void parsesHours()   { assertEquals(3 * 3_600_000L, DurationParser.parse("3h")); }
    @Test void parsesDays()    { assertEquals(8 * 86_400_000L, DurationParser.parse("8d")); }
    @Test void parsesWeeks()   { assertEquals(2 * 604_800_000L, DurationParser.parse("2w")); }
    @Test void parsesCombined() {
        assertEquals(604_800_000L + 2*86_400_000L + 6*3_600_000L, DurationParser.parse("1w2d6h"));
    }
    @Test void rejectsEmpty()   { assertThrows(IllegalArgumentException.class, () -> DurationParser.parse("")); }
    @Test void rejectsGarbage() { assertThrows(IllegalArgumentException.class, () -> DurationParser.parse("abc")); }
    @Test void rejectsBadUnit() { assertThrows(IllegalArgumentException.class, () -> DurationParser.parse("5y")); }
}
