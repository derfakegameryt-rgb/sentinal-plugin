package de.derfakegamer.sentinel.model;

import java.util.UUID;

public record Report(long id, UUID reporterUuid, String reporterName, UUID targetUuid,
                     String targetName, String reason, long createdAt, boolean handled, String handledBy) {}
