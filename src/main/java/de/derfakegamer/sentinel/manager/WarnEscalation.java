package de.derfakegamer.sentinel.manager;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.EscalationAction;
import de.derfakegamer.sentinel.model.PunishmentType;
import de.derfakegamer.sentinel.util.DurationParser;

public final class WarnEscalation {
    private final Sentinel plugin;

    public WarnEscalation(Sentinel plugin) { this.plugin = plugin; }

    /** Returns the action configured for exactly this warn count, or null. */
    public EscalationAction actionFor(int warnCount) {
        String spec = plugin.getConfig().getString("warn-actions." + warnCount);
        if (spec == null || spec.isBlank()) return null;
        String[] parts = spec.trim().split("\\s+");
        String type = parts[0].toLowerCase();
        return switch (type) {
            case "kick"     -> new EscalationAction(PunishmentType.KICK, 0, rest(parts, 1));
            case "ban"      -> new EscalationAction(PunishmentType.BAN, 0, rest(parts, 1));
            case "mute"     -> new EscalationAction(PunishmentType.MUTE, 0, rest(parts, 1));
            case "tempban"  -> temp(PunishmentType.BAN, parts);
            case "tempmute" -> temp(PunishmentType.MUTE, parts);
            default -> null;
        };
    }

    private EscalationAction temp(PunishmentType type, String[] parts) {
        if (parts.length < 2) return null;
        long ms;
        try { ms = DurationParser.parse(parts[1]); } catch (IllegalArgumentException e) { return null; }
        return new EscalationAction(type, ms, rest(parts, 2));
    }

    private String rest(String[] parts, int from) {
        return from >= parts.length ? "" : String.join(" ", java.util.Arrays.copyOfRange(parts, from, parts.length));
    }
}
