package de.derfakegamer.sentinel.model;

import java.util.UUID;

public final class Punishment {
    private final long id;
    private final PunishmentType type;
    private final UUID targetUuid;
    private final String targetName;
    private final String targetIp;     // nullable
    private final String reason;
    private final UUID issuerUuid;
    private final String issuerName;
    private final long createdAt;
    private final long expiresAt;       // 0 = permanent
    private final boolean active;
    private final String removedBy;     // nullable
    private final long removedAt;

    private Punishment(Builder b) {
        this.id = b.id; this.type = b.type; this.targetUuid = b.targetUuid;
        this.targetName = b.targetName; this.targetIp = b.targetIp; this.reason = b.reason;
        this.issuerUuid = b.issuerUuid; this.issuerName = b.issuerName; this.createdAt = b.createdAt;
        this.expiresAt = b.expiresAt; this.active = b.active; this.removedBy = b.removedBy;
        this.removedAt = b.removedAt;
    }

    public long id() { return id; }
    public PunishmentType type() { return type; }
    public UUID targetUuid() { return targetUuid; }
    public String targetName() { return targetName; }
    public String targetIp() { return targetIp; }
    public String reason() { return reason; }
    public UUID issuerUuid() { return issuerUuid; }
    public String issuerName() { return issuerName; }
    public long createdAt() { return createdAt; }
    public long expiresAt() { return expiresAt; }
    public boolean active() { return active; }
    public String removedBy() { return removedBy; }
    public long removedAt() { return removedAt; }

    public boolean isPermanent() { return expiresAt == 0; }
    public boolean isExpired(long now) { return !isPermanent() && now >= expiresAt; }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private long id; private PunishmentType type; private UUID targetUuid;
        private String targetName; private String targetIp; private String reason;
        private UUID issuerUuid; private String issuerName; private long createdAt;
        private long expiresAt; private boolean active; private String removedBy; private long removedAt;

        public Builder id(long v) { this.id = v; return this; }
        public Builder type(PunishmentType v) { this.type = v; return this; }
        public Builder targetUuid(UUID v) { this.targetUuid = v; return this; }
        public Builder targetName(String v) { this.targetName = v; return this; }
        public Builder targetIp(String v) { this.targetIp = v; return this; }
        public Builder reason(String v) { this.reason = v; return this; }
        public Builder issuerUuid(UUID v) { this.issuerUuid = v; return this; }
        public Builder issuerName(String v) { this.issuerName = v; return this; }
        public Builder createdAt(long v) { this.createdAt = v; return this; }
        public Builder expiresAt(long v) { this.expiresAt = v; return this; }
        public Builder active(boolean v) { this.active = v; return this; }
        public Builder removedBy(String v) { this.removedBy = v; return this; }
        public Builder removedAt(long v) { this.removedAt = v; return this; }
        public Punishment build() { return new Punishment(this); }
    }
}
