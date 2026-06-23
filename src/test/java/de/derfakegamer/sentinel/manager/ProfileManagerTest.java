package de.derfakegamer.sentinel.manager;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ProfileManagerTest {

    @Test
    void validNamesAccepted() {
        assertTrue(ProfileManager.isValidName("Notch"));
        assertTrue(ProfileManager.isValidName("a_B9"));
        assertTrue(ProfileManager.isValidName("ABCDEFGHIJKLMNOP")); // 16 chars
    }

    @Test
    void invalidNamesRejected() {
        assertFalse(ProfileManager.isValidName(null));
        assertFalse(ProfileManager.isValidName(""));
        assertFalse(ProfileManager.isValidName("has space"));
        assertFalse(ProfileManager.isValidName("toolongname123456")); // 17 chars
        assertFalse(ProfileManager.isValidName("col<red>or"));
        assertFalse(ProfileManager.isValidName("dash-name"));
    }
}
