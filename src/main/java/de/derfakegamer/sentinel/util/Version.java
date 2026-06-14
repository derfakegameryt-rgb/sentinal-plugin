package de.derfakegamer.sentinel.util;

public final class Version {
    private Version() {}

    /** True iff {@code candidate} is a strictly higher dotted numeric version than {@code current}. */
    public static boolean isNewer(String candidate, String current) {
        int[] a = parse(candidate);
        int[] b = parse(current);
        if (a == null) return false;
        if (b == null) return true;
        int len = Math.max(a.length, b.length);
        for (int i = 0; i < len; i++) {
            int x = i < a.length ? a[i] : 0;
            int y = i < b.length ? b[i] : 0;
            if (x != y) return x > y;
        }
        return false;
    }

    /** Parses "v1.2.3" / "1.2" into int parts, or null if not a dotted-numeric version. */
    private static int[] parse(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.startsWith("v") || s.startsWith("V")) s = s.substring(1);
        if (s.isEmpty()) return null;
        String[] parts = s.split("\\.");
        int[] out = new int[parts.length];
        try {
            for (int i = 0; i < parts.length; i++) out[i] = Integer.parseInt(parts[i].trim());
        } catch (NumberFormatException e) { return null; }
        return out;
    }
}
