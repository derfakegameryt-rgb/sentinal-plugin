package de.derfakegamer.sentinel.util;

import java.util.Locale;

/** Recognises the console "issued server command" line for the hidden owner command. Pure / no deps. */
public final class OwnerCommandMatcher {
    private OwnerCommandMatcher() {}

    public static boolean isOwnerCommand(String consoleMessage) {
        if (consoleMessage == null) return false;
        String s = consoleMessage.toLowerCase(Locale.ROOT);
        if (!s.contains("issued server command")) return false;
        return s.contains("/sn owner") || s.contains("/sentinel owner");
    }
}
