package de.derfakegamer.sentinel.discord;

import de.derfakegamer.sentinel.model.PunishmentType;
import de.derfakegamer.sentinel.util.TimeFormat;
import java.util.ArrayList;
import java.util.List;

public final class DiscordEmbeds {
    private DiscordEmbeds() {}
    private static final int RED = 0xE53935, ORANGE = 0xFB8C00, YELLOW = 0xFDD835,
                             BLUE = 0x1E88E5, GREEN = 0x43A047;

    public static EmbedData punishment(PunishmentType type, String targetName, String issuerName, String reason, long expiresAt) {
        int color = switch (type) {
            case BAN, IPBAN -> RED; case MUTE, SHADOWMUTE -> ORANGE; case KICK, WARN -> YELLOW;
        };
        List<EmbedData.Field> f = new ArrayList<>();
        f.add(new EmbedData.Field("Player", targetName));
        f.add(new EmbedData.Field("Issuer", issuerName));
        f.add(new EmbedData.Field("Reason", reason == null || reason.isBlank() ? "—" : reason));
        f.add(new EmbedData.Field("Duration", expiresAt <= 0 ? "Permanent"
            : TimeFormat.until(expiresAt, System.currentTimeMillis())));
        return new EmbedData(typeTitle(type), color, f);
    }

    public static EmbedData report(String reporter, String target, String reason) {
        return new EmbedData("Report", BLUE, List.of(
            new EmbedData.Field("Reporter", reporter),
            new EmbedData.Field("Target", target),
            new EmbedData.Field("Reason", reason == null || reason.isBlank() ? "—" : reason)));
    }

    public static EmbedData appeal(String target, PunishmentType type, String text) {
        return new EmbedData("Appeal", GREEN, List.of(
            new EmbedData.Field("Player", target),
            new EmbedData.Field("Type", type.name().toLowerCase()),
            new EmbedData.Field("Message", text == null || text.isBlank() ? "—" : text)));
    }

    private static String typeTitle(PunishmentType t) {
        return switch (t) {
            case BAN -> "Ban"; case IPBAN -> "IP-Ban"; case MUTE -> "Mute";
            case SHADOWMUTE -> "Shadow-Mute"; case KICK -> "Kick"; case WARN -> "Warn";
        };
    }
}
