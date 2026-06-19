package de.derfakegamer.sentinel.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DurationParser {
    private static final Pattern TOKEN = Pattern.compile("(\\d+)([smhdw])");

    private DurationParser() {}

    /** Parses e.g. "1w2d6h30m15s" into milliseconds. Throws on invalid input. */
    public static long parse(String input) {
        if (input == null || input.isBlank()) throw new IllegalArgumentException("empty duration");
        Matcher m = TOKEN.matcher(input.toLowerCase());
        long total = 0;
        int matchedChars = 0;
        while (m.find()) {
            matchedChars += m.group().length();
            try {
                long value = Long.parseLong(m.group(1));
                long part = switch (m.group(2)) {
                    case "s" -> Math.multiplyExact(value, 1_000L);
                    case "m" -> Math.multiplyExact(value, 60_000L);
                    case "h" -> Math.multiplyExact(value, 3_600_000L);
                    case "d" -> Math.multiplyExact(value, 86_400_000L);
                    case "w" -> Math.multiplyExact(value, 604_800_000L);
                    default -> throw new IllegalArgumentException("bad unit");
                };
                total = Math.addExact(total, part);
            } catch (ArithmeticException e) {
                throw new IllegalArgumentException("duration too large: " + input);
            }
        }
        if (matchedChars != input.length() || total <= 0)
            throw new IllegalArgumentException("invalid duration: " + input);
        return total;
    }
}
