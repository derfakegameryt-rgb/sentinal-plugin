package de.derfakegamer.sentinel.util;

/** Human-readable durations for ban/mute screens and notifications. */
public final class TimeFormat {
    private TimeFormat() {}

    /** "permanent" if expiresAt is 0, "expired" if already past, else the remaining duration. */
    public static String until(long expiresAt, long now) {
        if (expiresAt == 0) return "permanent";
        long ms = expiresAt - now;
        return ms <= 0 ? "expired" : duration(ms);
    }

    /** Formats a positive millisecond span like "1d 2h 3m" (or "45s"). */
    public static String duration(long ms) {
        long s = Math.max(0, ms) / 1000;
        long d = s / 86400; s %= 86400;
        long h = s / 3600;  s %= 3600;
        long m = s / 60;    s %= 60;
        StringBuilder b = new StringBuilder();
        if (d > 0) b.append(d).append("d ");
        if (h > 0) b.append(h).append("h ");
        if (m > 0) b.append(m).append("m ");
        if (b.length() == 0 || s > 0) b.append(s).append("s");
        return b.toString().trim();
    }
}
