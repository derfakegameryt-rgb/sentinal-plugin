package de.derfakegamer.sentinel.util;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Validates config.yml at startup and emits WARNING log messages for any problems
 * found. Fail-soft: never disables the plugin or throws.
 */
public final class ConfigValidator {

    private static final Set<String> VALID_ACTIONS = Set.of("kick", "ban", "tempban", "mute", "tempmute");
    private static final Pattern TIME_PATTERN = Pattern.compile("^\\d{1,2}:\\d{2}$");

    private ConfigValidator() {}

    public static void validate(FileConfiguration cfg, Logger log) {
        checkAppealsUrl(cfg, log);
        checkGuiSoundName(cfg, log);
        checkNonNegativeInts(cfg, log);
        checkAnnouncementsInterval(cfg, log);
        checkWarnActions(cfg, log);
        checkScheduledTasks(cfg, log);
        checkExemptUuids(cfg, log);
    }

    // 2. appeals.url
    private static void checkAppealsUrl(FileConfiguration cfg, Logger log) {
        String url = cfg.getString("appeals.url", "");
        if (url == null || url.isBlank()) return;
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            log.warning("Sentinel config: appeals.url should be a full URL starting with http:// or https://"
                    + " — got: " + url);
        }
    }

    // 3. gui.sound-name
    private static void checkGuiSoundName(FileConfiguration cfg, Logger log) {
        String name = cfg.getString("gui.sound-name", "");
        if (name == null || name.isBlank()) return;
        try {
            // Try Registry.SOUNDS.match first (1.21+); fall back to Sound.valueOf
            boolean valid = false;
            try {
                org.bukkit.Registry<?> registry = org.bukkit.Registry.SOUNDS;
                if (registry != null) {
                    org.bukkit.NamespacedKey key = org.bukkit.NamespacedKey.fromString(name.toLowerCase());
                    valid = key != null && registry.get(key) != null;
                }
            } catch (Throwable ignored) {
                // Registry unavailable (e.g. test JVM) — fall through to Sound.valueOf
            }
            if (!valid) {
                try {
                    org.bukkit.Sound.valueOf(name.toUpperCase());
                    valid = true;
                } catch (IllegalArgumentException ignored) {}
            }
            if (!valid) {
                log.warning("Sentinel config: gui.sound-name '" + name
                        + "' is not a known Bukkit Sound — the GUI click sound will silently not play.");
            }
        } catch (Throwable t) {
            // If Sound class is entirely unavailable (e.g. test JVM), skip the check
        }
    }

    // 4. Non-negative int fields
    private static void checkNonNegativeInts(FileConfiguration cfg, Logger log) {
        checkNonNegativeInt(cfg, log, "afk.minutes", 5);
        checkNonNegativeInt(cfg, log, "backup.keep", 5);
        checkNonNegativeInt(cfg, log, "logging.retention-days", 30);
        checkNonNegativeInt(cfg, log, "warns.expiry-days", 7);
        checkNonNegativeInt(cfg, log, "report.cooldown-seconds", 30);
        checkNonNegativeInt(cfg, log, "appeals.cooldown-seconds", 60);
    }

    private static void checkNonNegativeInt(FileConfiguration cfg, Logger log, String key, int defaultVal) {
        int value = cfg.getInt(key, defaultVal);
        if (value < 0) {
            log.warning("Sentinel config: " + key + " is " + value
                    + " — must be 0 or greater; using default (" + defaultVal + ") instead.");
        }
    }

    // 5. announcements.interval-seconds when enabled
    private static void checkAnnouncementsInterval(FileConfiguration cfg, Logger log) {
        if (!cfg.getBoolean("announcements.enabled", false)) return;
        long interval = cfg.getLong("announcements.interval-seconds", 300L);
        if (interval <= 0) {
            log.warning("Sentinel config: announcements.enabled is true but announcements.interval-seconds is "
                    + interval + " — must be > 0 for announcements to run.");
        }
    }

    // 6. warn-actions section
    private static void checkWarnActions(FileConfiguration cfg, Logger log) {
        ConfigurationSection section = cfg.getConfigurationSection("warn-actions");
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            String value = section.getString(key, "");
            if (value == null || value.isBlank()) {
                log.warning("Sentinel config: warn-actions." + key + " is blank — must be '<action> [duration] <reason>'.");
                continue;
            }
            String[] parts = value.trim().split("\\s+");
            if (parts.length < 2) {
                log.warning("Sentinel config: warn-actions." + key
                        + " is too short — must include an action and a reason: '" + value + "'.");
                continue;
            }
            String action = parts[0].toLowerCase();
            if (!VALID_ACTIONS.contains(action)) {
                log.warning("Sentinel config: warn-actions." + key
                        + " has unknown action '" + parts[0]
                        + "' — must be one of: kick, ban, tempban, mute, tempmute.");
                continue;
            }
            boolean needsDuration = action.equals("tempban") || action.equals("tempmute");
            if (needsDuration) {
                if (parts.length < 3) {
                    log.warning("Sentinel config: warn-actions." + key
                            + " — " + action + " requires a duration and a reason but none found: '" + value + "'.");
                    continue;
                }
                try {
                    DurationParser.parse(parts[1]);
                } catch (IllegalArgumentException e) {
                    log.warning("Sentinel config: warn-actions." + key
                            + " — '" + parts[1] + "' is not a valid duration for " + action
                            + " (e.g. 7d, 1h30m). Full value: '" + value + "'.");
                    continue;
                }
                // parts[2..] must form the reason — at least one token needed
                if (parts.length < 3 || parts[2].isBlank()) {
                    log.warning("Sentinel config: warn-actions." + key
                            + " — " + action + " is missing a reason after the duration: '" + value + "'.");
                }
            }
            // For non-temp actions: parts[1..] is the reason — already guaranteed by length >= 2
        }
    }

    // 7. scheduled-tasks list
    private static void checkScheduledTasks(FileConfiguration cfg, Logger log) {
        List<Map<?, ?>> tasks = cfg.getMapList("scheduled-tasks");
        for (int i = 0; i < tasks.size(); i++) {
            Map<?, ?> entry = tasks.get(i);
            Object doVal = entry.get("do");
            if (doVal == null || doVal.toString().isBlank()) {
                log.warning("Sentinel config: scheduled-tasks[" + i + "] is missing the required 'do' field.");
            }
            Object everyVal = entry.get("every");
            Object atVal    = entry.get("at");
            boolean hasEvery = everyVal != null && !everyVal.toString().isBlank();
            boolean hasAt    = atVal    != null && !atVal.toString().isBlank();
            if (!hasEvery && !hasAt) {
                log.warning("Sentinel config: scheduled-tasks[" + i
                        + "] must have either an 'every' (duration) or 'at' (HH:MM) field.");
                continue;
            }
            if (hasEvery) {
                try {
                    DurationParser.parse(everyVal.toString());
                } catch (IllegalArgumentException e) {
                    log.warning("Sentinel config: scheduled-tasks[" + i
                            + "] 'every' value '" + everyVal + "' is not a valid duration (e.g. 10m, 1h).");
                }
            }
            if (hasAt) {
                String at = atVal.toString().trim();
                if (!TIME_PATTERN.matcher(at).matches()) {
                    log.warning("Sentinel config: scheduled-tasks[" + i
                            + "] 'at' value '" + at + "' must match HH:MM (e.g. 06:00, 23:30).");
                } else {
                    String[] hm = at.split(":");
                    int hour   = Integer.parseInt(hm[0]);
                    int minute = Integer.parseInt(hm[1]);
                    if (hour > 23 || minute > 59) {
                        log.warning("Sentinel config: scheduled-tasks[" + i
                                + "] 'at' value '" + at + "' has out-of-range hour or minute.");
                    }
                }
            }
        }
    }

    // 8. exempt UUID list
    private static void checkExemptUuids(FileConfiguration cfg, Logger log) {
        List<String> exempt = cfg.getStringList("exempt");
        for (String entry : exempt) {
            if (entry == null || entry.isBlank()) continue;
            try {
                UUID.fromString(entry);
            } catch (IllegalArgumentException e) {
                log.warning("Sentinel config: exempt contains invalid UUID '" + entry
                        + "' — this entry will be ignored.");
            }
        }
    }
}
