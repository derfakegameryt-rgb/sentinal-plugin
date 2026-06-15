package de.derfakegamer.sentinel.storage;

import de.derfakegamer.sentinel.model.PlayerRecord;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class PlayerDao {
    private final Database db;

    public PlayerDao(Database db) { this.db = db; }

    public void upsert(UUID uuid, String name, String ip, long now) {
        synchronized (db) {
            String sql = """
                INSERT INTO players (uuid,name,last_ip,first_seen,last_seen)
                VALUES (?,?,?,?,?)
                ON CONFLICT(uuid) DO UPDATE SET name=excluded.name, last_ip=excluded.last_ip,
                    last_seen=excluded.last_seen""";
            try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                ps.setString(2, name);
                ps.setString(3, ip);
                ps.setLong(4, now);
                ps.setLong(5, now);
                ps.executeUpdate();
            } catch (SQLException e) { throw new RuntimeException(e); }
        }
    }

    public PlayerRecord byUuid(UUID uuid) {
        synchronized (db) {
            try (PreparedStatement ps = db.connection().prepareStatement("SELECT * FROM players WHERE uuid=?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) { return rs.next() ? map(rs) : null; }
            } catch (SQLException e) { throw new RuntimeException(e); }
        }
    }

    public PlayerRecord byName(String name) {
        synchronized (db) {
            try (PreparedStatement ps = db.connection().prepareStatement(
                    "SELECT * FROM players WHERE name=? COLLATE NOCASE LIMIT 1")) {
                ps.setString(1, name);
                try (ResultSet rs = ps.executeQuery()) { return rs.next() ? map(rs) : null; }
            } catch (SQLException e) { throw new RuntimeException(e); }
        }
    }

    public List<PlayerRecord> byIp(String ip) {
        synchronized (db) {
            List<PlayerRecord> out = new ArrayList<>();
            if (ip == null) return out;
            try (PreparedStatement ps = db.connection().prepareStatement(
                    "SELECT * FROM players WHERE last_ip=? ORDER BY last_seen DESC")) {
                ps.setString(1, ip);
                try (ResultSet rs = ps.executeQuery()) { while (rs.next()) out.add(map(rs)); }
            } catch (SQLException e) { throw new RuntimeException(e); }
            return out;
        }
    }

    public void addPlaytime(java.util.UUID uuid, long ms) {
        synchronized (db) {
            try (java.sql.PreparedStatement ps = db.connection().prepareStatement(
                    "UPDATE players SET playtime = playtime + ? WHERE uuid=?")) {
                ps.setLong(1, ms); ps.setString(2, uuid.toString()); ps.executeUpdate();
            } catch (java.sql.SQLException e) { throw new RuntimeException(e); }
        }
    }

    public long playtime(java.util.UUID uuid) {
        synchronized (db) {
            try (java.sql.PreparedStatement ps = db.connection().prepareStatement("SELECT playtime FROM players WHERE uuid=?")) {
                ps.setString(1, uuid.toString());
                try (java.sql.ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getLong(1) : 0; }
            } catch (java.sql.SQLException e) { throw new RuntimeException(e); }
        }
    }

    /** Top players by playtime: list of {name, playtimeMs}. */
    public java.util.List<de.derfakegamer.sentinel.model.PlayerRecord> topByPlaytime(int limit) {
        synchronized (db) {
            java.util.List<de.derfakegamer.sentinel.model.PlayerRecord> out = new java.util.ArrayList<>();
            try (java.sql.PreparedStatement ps = db.connection().prepareStatement(
                    "SELECT * FROM players ORDER BY playtime DESC LIMIT ?")) {
                ps.setInt(1, limit);
                try (java.sql.ResultSet rs = ps.executeQuery()) { while (rs.next()) out.add(map(rs)); }
            } catch (java.sql.SQLException e) { throw new RuntimeException(e); }
            return out;
        }
    }

    private PlayerRecord map(ResultSet rs) throws SQLException {
        return new PlayerRecord(UUID.fromString(rs.getString("uuid")), rs.getString("name"),
            rs.getString("last_ip"), rs.getLong("first_seen"), rs.getLong("last_seen"));
    }
}
