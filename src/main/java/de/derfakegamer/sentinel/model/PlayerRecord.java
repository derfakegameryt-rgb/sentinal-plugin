package de.derfakegamer.sentinel.model;

import java.util.UUID;

public record PlayerRecord(UUID uuid, String name, String lastIp, long firstSeen, long lastSeen) {}
