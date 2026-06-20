package de.derfakegamer.sentinel.discord;

import de.derfakegamer.sentinel.model.PunishmentType;

public final class NoopDiscordService implements DiscordService {
    @Override public void logPunishment(PunishmentType t, String tn, String in, String r, long e) {}
    @Override public void logReport(String rn, String tn, String r) {}
    @Override public void logAppeal(String tn, PunishmentType t, String x) {}
    @Override public void updatePresence(int online, int max) {}
    @Override public void shutdown() {}
}
