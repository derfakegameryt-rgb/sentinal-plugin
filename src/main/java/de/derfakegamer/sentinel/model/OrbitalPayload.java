package de.derfakegamer.sentinel.model;

public enum OrbitalPayload {
    TNT("TNT"),
    TNT_MINECART("TNT Minecart"),
    CHARGED_CREEPER("Charged Creeper");

    private final String label;
    OrbitalPayload(String label) { this.label = label; }
    public String label() { return label; }
}
