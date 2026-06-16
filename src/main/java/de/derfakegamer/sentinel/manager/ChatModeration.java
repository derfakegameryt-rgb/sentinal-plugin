package de.derfakegamer.sentinel.manager;

import de.derfakegamer.sentinel.Sentinel;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** Evaluates a normal chat message against the configured chat rules. Operators bypass this. */
public final class ChatModeration {
    public enum Action { ALLOW, BLOCK, CENSOR }

    public record Outcome(Action action, String messageKey, String censored) {
        static Outcome allow() { return new Outcome(Action.ALLOW, null, null); }
        static Outcome block(String key) { return new Outcome(Action.BLOCK, key, null); }
        static Outcome censor(String text) { return new Outcome(Action.CENSOR, null, text); }
    }

    // Matches a URL scheme / www. prefix, a domain ending in a known TLD, or an IPv4 address.
    // Restricting bare domains to known TLDs avoids false positives on ordinary chat
    // ("good.bye", "file.txt", "e.g.", "1.5") while still catching real server adverts.
    private static final Pattern ADVERT = Pattern.compile(
        "(?i)\\b((https?://|www\\.)\\S+"
        + "|[\\w-]+\\.(com|net|org|gg|io|me|tv|de|xyz|info|co|uk|eu|live|shop|store|app|dev"
        + "|fun|club|online|site|pro|cc|ru|nl|us|biz|gg|ly|to)\\b(/\\S*)?"
        + "|\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}(:\\d+)?)\\b");

    private final long slowmodeMs;
    private final boolean antiSpam; private final int maxRepeats;
    private final boolean antiAd; private final List<String> whitelist;
    private final boolean wordFilter; private final boolean censorMode; private final List<String> words;
    private final List<CensorRule> censorRules = new java.util.ArrayList<>();
    private final Set<String> normalizedWords = new java.util.HashSet<>();

    private final boolean capsFilter; private final int capsMinLength;
    private final double capsMaxRatio; private final boolean capsCensor;
    private final boolean floodFilter; private final int floodMaxRun;

    private record CensorRule(Pattern pattern, String mask) {}

    private final Map<UUID, Long> lastTime = new ConcurrentHashMap<>();
    private final Map<UUID, String> lastMsg = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> repeats = new ConcurrentHashMap<>();

    public ChatModeration(Sentinel plugin) {
        var c = plugin.getConfig();
        this.slowmodeMs = Math.max(0, c.getLong("chat.slowmode-seconds", 0)) * 1000L;
        this.antiSpam = c.getBoolean("chat.anti-spam.enabled", true);
        this.maxRepeats = Math.max(2, c.getInt("chat.anti-spam.max-repeats", 3));
        this.antiAd = c.getBoolean("chat.anti-advertising.enabled", true);
        this.whitelist = c.getStringList("chat.anti-advertising.whitelist");
        this.wordFilter = c.getBoolean("chat.word-filter.enabled", true);
        this.censorMode = c.getString("chat.word-filter.mode", "block").equalsIgnoreCase("censor");
        this.words = c.getStringList("chat.word-filter.words");
        for (String w : words) {
            if (w.isBlank()) continue;
            censorRules.add(new CensorRule(Pattern.compile("(?i)" + Pattern.quote(w)), "*".repeat(w.length())));
            String nw = normalize(w);
            if (!nw.isEmpty()) normalizedWords.add(nw);
        }
        this.capsFilter = c.getBoolean("chat.caps-filter.enabled", true);
        this.capsMinLength = c.getInt("chat.caps-filter.min-length", 8);
        this.capsMaxRatio = c.getDouble("chat.caps-filter.max-uppercase-ratio", 0.7);
        this.capsCensor = c.getString("chat.caps-filter.mode", "censor").equalsIgnoreCase("censor");
        this.floodFilter = c.getBoolean("chat.flood-filter.enabled", true);
        this.floodMaxRun = Math.max(2, c.getInt("chat.flood-filter.max-run", 5));
    }

    /** Drops a player's spam/slowmode tracking on quit so the per-player maps can't grow forever. */
    public void forget(UUID id) {
        lastTime.remove(id);
        lastMsg.remove(id);
        repeats.remove(id);
    }

    public Outcome evaluate(UUID id, String message, long now) {
        if (slowmodeMs > 0) {
            Long last = lastTime.get(id);
            if (last != null && now - last < slowmodeMs) return Outcome.block("chat-slowmode");
        }
        if (antiSpam) {
            if (message.equalsIgnoreCase(lastMsg.get(id))) {
                int n = repeats.merge(id, 1, Integer::sum);
                if (n + 1 >= maxRepeats) return Outcome.block("chat-blocked-spam");
            } else {
                repeats.put(id, 0);
            }
        }
        if (antiAd && ADVERT.matcher(message).find() && !whitelisted(message))
            return Outcome.block("chat-blocked-ad");
        if (wordFilter && !words.isEmpty()) {
            if (censorMode) {
                String censored = censor(message);
                if (!censored.equals(message)) { accept(id, message, now); return Outcome.censor(censored); }
                // A normalized-only hit (e.g. "b.a.d.w.o.r.d") can't be cleanly masked back onto
                // the original message, so fall back to BLOCK even in censor mode.
                if (containsNormalizedBadWord(message)) return Outcome.block("chat-blocked-word");
            } else if (containsBannedWord(message) || containsNormalizedBadWord(message)) {
                return Outcome.block("chat-blocked-word");
            }
        }
        if (capsFilter && message.length() >= capsMinLength && uppercaseRatio(message) > capsMaxRatio) {
            if (capsCensor) { accept(id, message, now); return Outcome.censor(message.toLowerCase()); }
            return Outcome.block("chat-blocked-caps");
        }
        if (floodFilter && longestRun(message) > floodMaxRun) return Outcome.block("chat-blocked-flood");
        accept(id, message, now);
        return Outcome.allow();
    }

    private void accept(UUID id, String message, long now) {
        lastTime.put(id, now);
        lastMsg.put(id, message);
    }

    private boolean whitelisted(String message) {
        String low = message.toLowerCase();
        for (String w : whitelist) if (!w.isBlank() && low.contains(w.toLowerCase())) return true;
        return false;
    }

    private boolean containsBannedWord(String message) {
        String low = message.toLowerCase();
        for (String w : words) if (!w.isBlank() && low.contains(w.toLowerCase())) return true;
        return false;
    }

    private String censor(String message) {
        String out = message;
        for (CensorRule r : censorRules) {
            out = r.pattern().matcher(out).replaceAll(java.util.regex.Matcher.quoteReplacement(r.mask()));
        }
        return out;
    }

    private boolean containsNormalizedBadWord(String message) {
        if (normalizedWords.isEmpty()) return false;
        String norm = normalize(message);
        for (String w : normalizedWords) if (norm.contains(w)) return true;
        return false;
    }

    /** Fraction of letters that are uppercase, 0 if there are no letters. */
    static double uppercaseRatio(String s) {
        int letters = 0, upper = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (Character.isLetter(ch)) { letters++; if (Character.isUpperCase(ch)) upper++; }
        }
        return letters == 0 ? 0 : (double) upper / letters;
    }

    /** Longest run of the same (case-insensitive) character. */
    static int longestRun(String s) {
        int best = 0, run = 0; char prev = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = Character.toLowerCase(s.charAt(i));
            if (ch == prev) run++; else { run = 1; prev = ch; }
            if (run > best) best = run;
        }
        return best;
    }

    /** Normalizes leetspeak/obfuscation so the word filter still matches: lowercases, maps common
     *  substitutions, and strips non-alphanumerics + collapses repeated letters. */
    static String normalize(String s) {
        StringBuilder b = new StringBuilder();
        char last = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = Character.toLowerCase(s.charAt(i));
            char mapped = switch (ch) {
                case '0' -> 'o'; case '1','!','|' -> 'i'; case '3' -> 'e';
                case '4','@' -> 'a'; case '5','$' -> 's'; case '7' -> 't';
                default -> ch;
            };
            if (!Character.isLetterOrDigit(mapped)) continue; // drop separators like . _ - spaces
            if (mapped == last) continue;                      // collapse repeats: fuuuck -> fuck
            b.append(mapped);
            last = mapped;
        }
        return b.toString();
    }
}
