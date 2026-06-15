package de.derfakegamer.sentinel.storage;

import de.derfakegamer.sentinel.model.OrbitalPayload;
import de.derfakegamer.sentinel.model.ScheduledStrike;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public final class ScheduledStrikeDao {
    private final Database db;
    public ScheduledStrikeDao(Database db) { this.db = db; }

    public long insert(String world, int x, int z, String payload, long fireAt) {
        synchronized (db) {
            String sql = "INSERT INTO scheduled_strikes (world,x,z,payload,fire_at) VALUES (?,?,?,?,?)";
            try (PreparedStatement ps = db.connection().prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, world);
                ps.setInt(2, x);
                ps.setInt(3, z);
                ps.setString(4, payload);
                ps.setLong(5, fireAt);
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    return rs.next() ? rs.getLong(1) : -1L;
                }
            } catch (SQLException e) { throw new RuntimeException(e); }
        }
    }

    /** All pending strikes, ordered by fire_at ascending. */
    public List<ScheduledStrike> pending() {
        synchronized (db) {
            List<ScheduledStrike> out = new ArrayList<>();
            String sql = "SELECT id,world,x,z,payload,fire_at FROM scheduled_strikes ORDER BY fire_at ASC";
            try (PreparedStatement ps = db.connection().prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new ScheduledStrike(
                        rs.getLong("id"),
                        rs.getString("world"),
                        rs.getInt("x"),
                        rs.getInt("z"),
                        OrbitalPayload.valueOf(rs.getString("payload")),
                        rs.getLong("fire_at")));
                }
            } catch (SQLException e) { throw new RuntimeException(e); }
            return out;
        }
    }

    public int delete(long id) {
        synchronized (db) {
            try (PreparedStatement ps = db.connection().prepareStatement("DELETE FROM scheduled_strikes WHERE id=?")) {
                ps.setLong(1, id);
                return ps.executeUpdate();
            } catch (SQLException e) { throw new RuntimeException(e); }
        }
    }
}
