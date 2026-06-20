package de.derfakegamer.sentinel.discord;

import de.derfakegamer.sentinel.model.PunishmentType;

/** Sends Sentinel events to Discord. Implementations must be fail-soft (never throw into gameplay). */
public interface DiscordService {
    void logPunishment(PunishmentType type, String targetName, String issuerName, String reason, long expiresAt);
    void logReport(String reporterName, String targetName, String reason);
    void logAppeal(String targetName, PunishmentType type, String text);
    void updatePresence(int online, int max);
    void shutdown();
}
