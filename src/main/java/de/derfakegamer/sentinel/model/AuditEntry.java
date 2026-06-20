package de.derfakegamer.sentinel.model;
public record AuditEntry(long id, String actor, String action, String target, String details, long createdAt) {}
