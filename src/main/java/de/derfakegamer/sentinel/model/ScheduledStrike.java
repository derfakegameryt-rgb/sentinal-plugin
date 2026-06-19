package de.derfakegamer.sentinel.model;

public record ScheduledStrike(long id, String world, int x, int z, OrbitalPayload payload, long fireAt) {}
