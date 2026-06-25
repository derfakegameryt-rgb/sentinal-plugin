package de.derfakegamer.sentinel.util;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class ConfigValidatorTest {

    private Logger log;
    private List<LogRecord> records;

    @BeforeEach
    void setup() {
        records = new ArrayList<>();
        log = Logger.getAnonymousLogger();
        log.setUseParentHandlers(false);
        log.setLevel(Level.ALL);
        log.addHandler(new Handler() {
            @Override public void publish(LogRecord r) { records.add(r); }
            @Override public void flush() {}
            @Override public void close() {}
        });
    }

    @AfterEach
    void clearHandlers() {
        for (var h : log.getHandlers()) log.removeHandler(h);
    }

    private YamlConfiguration load(String yaml) {
        return YamlConfiguration.loadConfiguration(new StringReader(yaml));
    }

    private long warningCount() {
        return records.stream().filter(r -> r.getLevel() == Level.WARNING).count();
    }

    private boolean hasWarningContaining(String fragment) {
        return records.stream()
                .filter(r -> r.getLevel() == Level.WARNING)
                .anyMatch(r -> r.getMessage().toLowerCase().contains(fragment.toLowerCase()));
    }

    // -----------------------------------------------------------------------
    // 1. A fully-valid config produces ZERO warnings
    // -----------------------------------------------------------------------
    @Test
    void validConfigProducesNoWarnings() {
        String yaml = """
                appeals:
                  url: "https://example.com/appeals"
                gui:
                  sound-name: ""
                backup:
                  keep: 5
                logging:
                  retention-days: 30
                """;
        ConfigValidator.validate(load(yaml), log);
        assertEquals(0, warningCount(), "Expected no warnings for a valid config, got: " + records);
    }

    // -----------------------------------------------------------------------
    // 3. Negative backup.keep warns
    // -----------------------------------------------------------------------
    @Test
    void negativeBackupKeepWarns() {
        String yaml = "backup:\n  keep: -1\n";
        ConfigValidator.validate(load(yaml), log);
        assertTrue(hasWarningContaining("backup.keep"), "Expected warning for negative backup.keep");
    }

    @Test
    void zeroBackupKeepNoWarn() {
        String yaml = "backup:\n  keep: 0\n";
        ConfigValidator.validate(load(yaml), log);
        assertEquals(0, warningCount(), "Zero backup.keep should not warn");
    }

    // -----------------------------------------------------------------------
    // 4. Bad warn-actions value warns
    // -----------------------------------------------------------------------
    @Test
    void badWarnActionDurationWarns() {
        String yaml = """
                warn-actions:
                  "3": "tempban notaduration Please read the rules"
                """;
        ConfigValidator.validate(load(yaml), log);
        assertTrue(warningCount() > 0, "Expected a warning for invalid duration in warn-actions");
    }

    @Test
    void validWarnActionNoWarn() {
        String yaml = """
                warn-actions:
                  "1": "kick Please read the rules"
                  "2": "tempban 7d Repeat offender"
                  "3": "ban Severe violation"
                """;
        ConfigValidator.validate(load(yaml), log);
        assertEquals(0, warningCount(), "Valid warn-actions should produce no warnings");
    }

    @Test
    void unknownWarnActionTypeWarns() {
        String yaml = """
                warn-actions:
                  "1": "jail 1h Bad player"
                """;
        ConfigValidator.validate(load(yaml), log);
        assertTrue(warningCount() > 0, "Expected a warning for unknown action type");
    }

    // -----------------------------------------------------------------------
    // 5. Bad exempt UUID warns
    // -----------------------------------------------------------------------
    @Test
    void badExemptUuidWarns() {
        String yaml = """
                exempt:
                  - "not-a-valid-uuid"
                """;
        ConfigValidator.validate(load(yaml), log);
        assertTrue(hasWarningContaining("exempt"), "Expected a warning for invalid UUID in exempt list");
    }

    @Test
    void validExemptUuidNoWarn() {
        String yaml = """
                exempt:
                  - "550e8400-e29b-41d4-a716-446655440000"
                """;
        ConfigValidator.validate(load(yaml), log);
        assertEquals(0, warningCount(), "Valid UUID in exempt should not warn");
    }

    // -----------------------------------------------------------------------
    // 6. scheduled-tasks: missing 'do' warns; bad every/at warns
    // -----------------------------------------------------------------------
    @Test
    void scheduledTaskMissingDoWarns() {
        String yaml = """
                scheduled-tasks:
                  - every: "10m"
                """;
        ConfigValidator.validate(load(yaml), log);
        assertTrue(hasWarningContaining("do"), "Expected warning for scheduled-task missing 'do' field");
    }

    @Test
    void scheduledTaskBadEveryWarns() {
        String yaml = """
                scheduled-tasks:
                  - do: "say hello"
                    every: "notaduration"
                """;
        ConfigValidator.validate(load(yaml), log);
        assertTrue(warningCount() > 0, "Expected warning for invalid 'every' duration in scheduled-tasks");
    }

    @Test
    void scheduledTaskBadAtFormatWarns() {
        String yaml = """
                scheduled-tasks:
                  - do: "say hello"
                    at: "25:99"
                """;
        ConfigValidator.validate(load(yaml), log);
        assertTrue(warningCount() > 0, "Expected warning for out-of-range 'at' time in scheduled-tasks");
    }

    @Test
    void scheduledTaskMissingEveryAndAtWarns() {
        String yaml = """
                scheduled-tasks:
                  - do: "say hello"
                """;
        ConfigValidator.validate(load(yaml), log);
        assertTrue(warningCount() > 0, "Expected warning when scheduled-task has neither 'every' nor 'at'");
    }

    @Test
    void scheduledTaskValidNoWarn() {
        String yaml = """
                scheduled-tasks:
                  - do: "say hello"
                    every: "10m"
                  - do: "say goodnight"
                    at: "22:00"
                """;
        ConfigValidator.validate(load(yaml), log);
        assertEquals(0, warningCount(), "Valid scheduled-tasks should produce no warnings, got: " + records);
    }

    // -----------------------------------------------------------------------
    // 7. gui.sound-name: must not throw on a bogus value in the test JVM
    // -----------------------------------------------------------------------
    @Test
    void bogusGuiSoundNameDoesNotThrow() {
        String yaml = "gui:\n  sound-name: \"DEFINITELY_NOT_A_REAL_SOUND_XYZ\"\n";
        assertDoesNotThrow(() -> ConfigValidator.validate(load(yaml), log),
                "ConfigValidator must not throw even when the sound name is bogus in a headless test JVM");
        // No assertion on warning count — Sound registry may be partially available
    }

    // -----------------------------------------------------------------------
    // 9. appeals.url bad scheme warns
    // -----------------------------------------------------------------------
    @Test
    void badAppealsUrlSchemeWarns() {
        String yaml = "appeals:\n  url: \"ftp://example.com/appeals\"\n";
        ConfigValidator.validate(load(yaml), log);
        assertTrue(hasWarningContaining("appeals.url"), "Expected warning for non-http appeals.url");
    }

    // -----------------------------------------------------------------------
    // 10. Clamp out-of-range numbers to defaults
    // -----------------------------------------------------------------------
    @Test void negativeBackupKeepIsClampedToDefault() {
        var cfg = load("backup:\n  keep: -1\n");
        ConfigValidator.validate(cfg, log);
        assertEquals(5, cfg.getInt("backup.keep"), "negative value must be clamped to the default");
    }

    @Test void validIntValueIsNotMutated() {
        var cfg = load("backup:\n  keep: 3\n");
        ConfigValidator.validate(cfg, log);
        assertEquals(3, cfg.getInt("backup.keep"), "valid value must be left untouched");
        assertEquals(0, warningCount());
    }

}
