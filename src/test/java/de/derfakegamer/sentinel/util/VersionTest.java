package de.derfakegamer.sentinel.util;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VersionTest {
    @Test void higherPatchIsNewer()  { assertTrue(Version.isNewer("1.0.1", "1.0.0")); }
    @Test void higherMinorIsNewer()  { assertTrue(Version.isNewer("1.1.0", "1.0.9")); }
    @Test void higherMajorIsNewer()  { assertTrue(Version.isNewer("2.0.0", "1.9.9")); }
    @Test void equalIsNotNewer()     { assertFalse(Version.isNewer("1.0.0", "1.0.0")); }
    @Test void lowerIsNotNewer()     { assertFalse(Version.isNewer("1.0.0", "1.0.1")); }
    @Test void leadingVIsStripped()  { assertTrue(Version.isNewer("v1.2.0", "1.1.0")); }
    @Test void differentLengths()    { assertTrue(Version.isNewer("1.2", "1.1.9")); assertFalse(Version.isNewer("1.2", "1.2.0")); }
    @Test void garbageIsNotNewer()   { assertFalse(Version.isNewer("abc", "1.0.0")); }
}
