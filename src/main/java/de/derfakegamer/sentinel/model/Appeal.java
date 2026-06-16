package de.derfakegamer.sentinel.model;

import java.util.UUID;

public record Appeal(long id, long punishmentId, UUID targetUuid, String targetName,
                     PunishmentType type, String text, String status,
                     long createdAt, String handledBy, long handledAt) {
    public boolean isOpen() { return "OPEN".equals(status); }
}
