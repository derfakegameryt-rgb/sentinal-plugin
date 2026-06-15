package de.derfakegamer.sentinel.model;

import java.util.UUID;

public record ChatLogEntry(long id, UUID uuid, String name, String kind, String text, long createdAt) {}
