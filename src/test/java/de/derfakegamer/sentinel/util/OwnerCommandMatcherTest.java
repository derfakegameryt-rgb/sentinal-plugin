package de.derfakegamer.sentinel.util;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class OwnerCommandMatcherTest {
    @Test void matchesOwnerCommandConsoleLines() {
        assertTrue(OwnerCommandMatcher.isOwnerCommand("Admin issued server command: /sn owner"));
        assertTrue(OwnerCommandMatcher.isOwnerCommand("Bob issued server command: /sentinel owner"));
        assertTrue(OwnerCommandMatcher.isOwnerCommand("Admin issued server command: /SN OWNER"));
    }

    @Test void ignoresEverythingElse() {
        assertFalse(OwnerCommandMatcher.isOwnerCommand(null));
        assertFalse(OwnerCommandMatcher.isOwnerCommand("Admin issued server command: /sn reload"));
        assertFalse(OwnerCommandMatcher.isOwnerCommand("Admin issued server command: /ban Bob"));
        assertFalse(OwnerCommandMatcher.isOwnerCommand("player said: /sn owner")); // not a command-issue line
    }
}
