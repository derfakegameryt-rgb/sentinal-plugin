package de.derfakegamer.sentinel.model;

public record EscalationAction(PunishmentType type, long durationMs, String reason) {}
