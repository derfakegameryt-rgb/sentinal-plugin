package de.derfakegamer.sentinel.storage;

import de.derfakegamer.sentinel.model.ActionCount;
import de.derfakegamer.sentinel.model.ActorCount;
import de.derfakegamer.sentinel.model.AuditEntry;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public final class AuditDao {
    private final Database db;
    public AuditDao(Database db) { this.db = db; }

    public void insert(String actor, String action, String target, String details, long createdAt) {
        synchronized (db) {
            String sql = "INSERT INTO audit (actor,action,target,details,created_at) VALUES (?,?,?,?,?)";
            try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
                ps.setString(1, actor);
                ps.setString(2, action);
                ps.setString(3, target);
                ps.setString(4, details);
                ps.setLong(5, createdAt);
                ps.executeUpdate();
            } catch (SQLException e) { throw new RuntimeException(e); }
        }
    }

    public List<AuditEntry> recent(int limit, int offset) {
        synchronized (db) {
            String sql = "SELECT * FROM audit ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?";
            try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
                ps.setInt(1, limit); ps.setInt(2, offset);
                return readAll(ps);
            } catch (SQLException e) { throw new RuntimeException(e); }
        }
    }

    public List<AuditEntry> recentForTarget(String target, int limit) {
        synchronized (db) {
            String sql = "SELECT * FROM audit WHERE target=? ORDER BY created_at DESC, id DESC LIMIT ?";
            try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
                ps.setString(1, target); ps.setInt(2, limit);
                return readAll(ps);
            } catch (SQLException e) { throw new RuntimeException(e); }
        }
    }

    public List<ActorCount> topActors(long sinceMillis, int limit) {
        synchronized (db) {
            String sql = "SELECT actor, COUNT(*) AS c FROM audit WHERE created_at >= ? GROUP BY actor ORDER BY c DESC LIMIT ?";
            try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
                ps.setLong(1, sinceMillis); ps.setInt(2, limit);
                List<ActorCount> out = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(new ActorCount(rs.getString("actor"), rs.getInt("c")));
                }
                return out;
            } catch (SQLException e) { throw new RuntimeException(e); }
        }
    }

    public List<ActionCount> countsByAction(long sinceMillis) {
        synchronized (db) {
            String sql = "SELECT action, COUNT(*) AS c FROM audit WHERE created_at >= ? GROUP BY action ORDER BY c DESC";
            try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
                ps.setLong(1, sinceMillis);
                List<ActionCount> out = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(new ActionCount(rs.getString("action"), rs.getInt("c")));
                }
                return out;
            } catch (SQLException e) { throw new RuntimeException(e); }
        }
    }

    private List<AuditEntry> readAll(PreparedStatement ps) throws SQLException {
        List<AuditEntry> out = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.add(new AuditEntry(rs.getLong("id"), rs.getString("actor"),
                rs.getString("action"), rs.getString("target"), rs.getString("details"), rs.getLong("created_at")));
        }
        return out;
    }
}
