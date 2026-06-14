package de.derfakegamer.sentinel.model;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class PunishmentTest {
    @Test void permanentNeverExpires() {
        Punishment p = base().expiresAt(0).build();
        assertFalse(p.isExpired(System.currentTimeMillis()));
        assertTrue(p.isPermanent());
    }
    @Test void temporaryExpires() {
        Punishment p = base().expiresAt(1000).build();
        assertTrue(p.isExpired(1001));
        assertFalse(p.isExpired(999));
    }
    private Punishment.Builder base() {
        return Punishment.builder()
            .type(PunishmentType.BAN).targetUuid(UUID.randomUUID()).targetName("Notch")
            .reason("test").issuerUuid(UUID.randomUUID()).issuerName("Admin")
            .createdAt(0).active(true);
    }
}
