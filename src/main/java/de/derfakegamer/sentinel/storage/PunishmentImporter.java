package de.derfakegamer.sentinel.storage;

import de.derfakegamer.sentinel.model.Punishment;
import de.derfakegamer.sentinel.model.PunishmentType;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.UUID;

/**
 * One-shot importer that copies punishment history out of another ban plugin's SQLite database
 * into Sentinel's {@code punishments} table.
 *
 * Only SQLite source files are supported — Sentinel bundles the SQLite driver and ships no MySQL
 * driver by design, so MySQL-backed installs must first export to SQLite. The mapping targets the
 * modern LiteBans and AdvancedBan schemas; rows that cannot be mapped (e.g. notes, unparseable
 * UUIDs) are skipped and counted rather than failing the whole run.
 */
public final class PunishmentImporter {
    /** A name-only operator (console, or a plugin with no stored issuer UUID) maps to the zero UUID. */
    private static final UUID CONSOLE = new UUID(0, 0);

    public enum Source { LITEBANS, ADVANCEDBAN }

    public record Result(int imported, int skipped) {
        public int total() { return imported + skipped; }
    }

    private final PunishmentDao dao;

    public PunishmentImporter(PunishmentDao dao) { this.dao = dao; }

    /** Opens {@code sourceDb} as a read-only SQLite file and imports from it. */
    public Result importFromFile(Source source, File sourceDb) throws SQLException {
        if (!sourceDb.isFile())
            throw new SQLException("source database not found: " + sourceDb.getAbsolutePath());
        try (Connection src = DriverManager.getConnection("jdbc:sqlite:" + sourceDb.getAbsolutePath())) {
            return importFrom(source, src);
        }
    }

    /** Imports from an already-open source connection. Testable without touching the filesystem. */
    public Result importFrom(Source source, Connection src) throws SQLException {
        return switch (source) {
            case LITEBANS -> importLiteBans(src);
            case ADVANCEDBAN -> importAdvancedBan(src);
        };
    }

    // ---- LiteBans -------------------------------------------------------------------------

    private Result importLiteBans(Connection src) throws SQLException {
        int imported = 0, skipped = 0;
        // bans: an `ipban` flag distinguishes UUID bans from IP bans.
        int[] r;
        r = importLiteBansTable(src, "litebans_bans", PunishmentType.BAN, true);
        imported += r[0]; skipped += r[1];
        r = importLiteBansTable(src, "litebans_mutes", PunishmentType.MUTE, false);
        imported += r[0]; skipped += r[1];
        r = importLiteBansTable(src, "litebans_warnings", PunishmentType.WARN, false);
        imported += r[0]; skipped += r[1];
        r = importLiteBansTable(src, "litebans_kicks", PunishmentType.KICK, false);
        imported += r[0]; skipped += r[1];
        return new Result(imported, skipped);
    }

    private int[] importLiteBansTable(Connection src, String table, PunishmentType type, boolean hasIpban)
            throws SQLException {
        int imported = 0, skipped = 0;
        // All four LiteBans tables share this column set; only the bans table carries `ipban`.
        String cols = "uuid, ip, reason, banned_by_uuid, banned_by_name, time, until, active"
            + (hasIpban ? ", ipban" : "");
        String sql = "SELECT " + cols + " FROM " + table;
        try (Statement st = src.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                String rawUuid = rs.getString("uuid");
                String ip = rs.getString("ip");
                boolean ipban = hasIpban && rs.getBoolean("ipban");
                PunishmentType t = ipban ? PunishmentType.IPBAN : type;

                UUID target = parseUuid(rawUuid);
                if (target == null) {
                    if (ipban && ip != null && !ip.isBlank()) target = CONSOLE;
                    else { skipped++; continue; }
                }
                long created = rs.getLong("time");
                long until = rs.getLong("until");
                boolean active = rs.getInt("active") == 1;
                dao.insert(Punishment.builder()
                    .type(t)
                    .targetUuid(target)
                    .targetName("unknown")          // LiteBans does not store the player name on the row
                    .targetIp(ipban ? ip : null)
                    .reason(orNull(rs.getString("reason")))
                    .issuerUuid(parseUuidOr(rs.getString("banned_by_uuid"), CONSOLE))
                    .issuerName(orDefault(rs.getString("banned_by_name"), "Console"))
                    .createdAt(created)
                    .expiresAt(normalizeExpiry(until))
                    .active(active)
                    .build());
                imported++;
            }
        }
        return new int[]{imported, skipped};
    }

    // ---- AdvancedBan ----------------------------------------------------------------------

    private Result importAdvancedBan(Connection src) throws SQLException {
        int imported = 0, skipped = 0;
        int[] r;
        r = importAdvancedBanTable(src, "Punishments", true);
        imported += r[0]; skipped += r[1];
        r = importAdvancedBanTable(src, "PunishmentHistory", false);
        imported += r[0]; skipped += r[1];
        return new Result(imported, skipped);
    }

    private int[] importAdvancedBanTable(Connection src, String table, boolean active) throws SQLException {
        int imported = 0, skipped = 0;
        String sql = "SELECT name, uuid, reason, operator, punishmentType, start, end FROM " + table;
        try (Statement st = src.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                PunishmentType t = mapAdvancedBanType(rs.getString("punishmentType"));
                if (t == null) { skipped++; continue; }          // NOTE / unknown -> skip
                UUID target = parseUuid(rs.getString("uuid"));
                if (target == null) { skipped++; continue; }
                dao.insert(Punishment.builder()
                    .type(t)
                    .targetUuid(target)
                    .targetName(orDefault(rs.getString("name"), "unknown"))
                    .reason(orNull(rs.getString("reason")))
                    .issuerUuid(CONSOLE)                          // AdvancedBan stores operator name only
                    .issuerName(orDefault(rs.getString("operator"), "Console"))
                    .createdAt(rs.getLong("start"))
                    .expiresAt(normalizeExpiry(rs.getLong("end")))
                    .active(active)
                    .build());
                imported++;
            }
        }
        return new int[]{imported, skipped};
    }

    /** AdvancedBan punishmentType -> Sentinel type, or null to skip (NOTE / unknown). */
    static PunishmentType mapAdvancedBanType(String raw) {
        if (raw == null) return null;
        return switch (raw.toUpperCase(Locale.ROOT)) {
            case "BAN", "TEMP_BAN" -> PunishmentType.BAN;
            case "IP_BAN", "TEMP_IP_BAN" -> PunishmentType.IPBAN;
            case "MUTE", "TEMP_MUTE" -> PunishmentType.MUTE;
            case "WARNING", "TEMP_WARNING" -> PunishmentType.WARN;
            case "KICK" -> PunishmentType.KICK;
            default -> null;
        };
    }

    // ---- shared helpers -------------------------------------------------------------------

    /** LiteBans/AdvancedBan use -1 for "permanent"; Sentinel uses 0. Anything <= 0 is permanent. */
    static long normalizeExpiry(long sourceUntil) { return sourceUntil <= 0 ? 0 : sourceUntil; }

    /** Parses a UUID with or without dashes; returns null if it is not a valid UUID. */
    static UUID parseUuid(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty()) return null;
        try {
            if (t.length() == 32 && t.indexOf('-') < 0) {
                t = t.substring(0, 8) + "-" + t.substring(8, 12) + "-" + t.substring(12, 16)
                    + "-" + t.substring(16, 20) + "-" + t.substring(20);
            }
            return UUID.fromString(t);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static UUID parseUuidOr(String s, UUID fallback) {
        UUID u = parseUuid(s);
        return u != null ? u : fallback;
    }

    private static String orNull(String s) { return (s == null || s.isBlank()) ? null : s; }

    private static String orDefault(String s, String def) { return (s == null || s.isBlank()) ? def : s; }
}
