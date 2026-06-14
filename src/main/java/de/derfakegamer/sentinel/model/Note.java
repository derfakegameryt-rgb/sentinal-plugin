package de.derfakegamer.sentinel.model;

import java.util.UUID;

public record Note(long id, UUID targetUuid, String author, String text, long createdAt) {}
