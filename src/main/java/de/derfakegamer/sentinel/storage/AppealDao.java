package de.derfakegamer.sentinel.storage;

import de.derfakegamer.sentinel.model.Appeal;
import de.derfakegamer.sentinel.model.PunishmentType;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class AppealDao {
    private final Database db;

    public AppealDao(Database db) { this.db = db; }

    public long insert(Appeal a) {
        {
            String sql = """
                INSERT INTO appeals (punishment_id,target_uuid,target_name,type,text,status,created_at,handled_by,handled_at)
                VALUES (?,?,?,?,?,'OPEN',?,?,0)""";
            try (PreparedStatement ps = db.connection().prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, a.punishmentId());
                ps.setString(2, a.targetUuid().toString());
                ps.setString(3, a.targetName());
                ps.setString(4, a.type().name());
                ps.setString(5, a.text());
                ps.setLong(6, a.createdAt());
                ps.setString(7, a.handledBy());
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) { return keys.next() ? keys.getLong(1) : -1; }
            } catch (SQLException e) { throw new RuntimeException(e); }
        }
    }

    public List<Appeal> findOpen() {
        {
            String sql = "SELECT * FROM appeals WHERE status='OPEN' ORDER BY created_at ASC";
            List<Appeal> out = new ArrayList<>();
            try (PreparedStatement ps = db.connection().prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(map(rs));
            } catch (SQLException e) { throw new RuntimeException(e); }
            return out;
        }
    }

    public Appeal byId(long id) {
        {
            String sql = "SELECT * FROM appeals WHERE id=?";
            try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? map(rs) : null;
                }
            } catch (SQLException e) { throw new RuntimeException(e); }
        }
    }

    public boolean hasOpenForTarget(UUID uuid) {
        {
            String sql = "SELECT 1 FROM appeals WHERE target_uuid=? AND status='OPEN' LIMIT 1";
            try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
            } catch (SQLException e) { throw new RuntimeException(e); }
        }
    }

    public void setStatus(long id, String status, String handledBy, long handledAt) {
        {
            String sql = "UPDATE appeals SET status=?, handled_by=?, handled_at=? WHERE id=?";
            try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
                ps.setString(1, status);
                ps.setString(2, handledBy);
                ps.setLong(3, handledAt);
                ps.setLong(4, id);
                ps.executeUpdate();
            } catch (SQLException e) { throw new RuntimeException(e); }
        }
    }

    private Appeal map(ResultSet rs) throws SQLException {
        return new Appeal(rs.getLong("id"), rs.getLong("punishment_id"),
            UUID.fromString(rs.getString("target_uuid")), rs.getString("target_name"),
            PunishmentType.valueOf(rs.getString("type")), rs.getString("text"),
            rs.getString("status"), rs.getLong("created_at"),
            rs.getString("handled_by"), rs.getLong("handled_at"));
    }
}
