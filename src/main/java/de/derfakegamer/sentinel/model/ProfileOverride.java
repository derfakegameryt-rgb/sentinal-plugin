package de.derfakegamer.sentinel.model;

import java.util.UUID;

/** A staff-set display-name and/or skin override for a player; either field may be null. */
public record ProfileOverride(UUID uuid, String displayName, String skinValue,
                              String skinSignature, String updatedBy, long updatedAt) {}
