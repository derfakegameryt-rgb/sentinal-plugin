package de.derfakegamer.sentinel.storage;

import de.derfakegamer.sentinel.model.Report;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class ReportDao {
    private final Database db;

    public ReportDao(Database db) { this.db = db; }

    public long insert(Report r) {
        synchronized (db) {
            String sql = """
                INSERT INTO reports (reporter_uuid,reporter_name,target_uuid,target_name,reason,created_at,handled,handled_by)
                VALUES (?,?,?,?,?,?,?,?)""";
            try (PreparedStatement ps = db.connection().prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, r.reporterUuid().toString());
                ps.setString(2, r.reporterName());
                ps.setString(3, r.targetUuid().toString());
                ps.setString(4, r.targetName());
                ps.setString(5, r.reason());
                ps.setLong(6, r.createdAt());
                ps.setInt(7, r.handled() ? 1 : 0);
                ps.setString(8, r.handledBy());
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) { return keys.next() ? keys.getLong(1) : -1; }
            } catch (SQLException e) { throw new RuntimeException(e); }
        }
    }

    public List<Report> findOpen() {
        synchronized (db) {
            String sql = "SELECT * FROM reports WHERE handled=0 ORDER BY created_at ASC";
            List<Report> out = new ArrayList<>();
            try (PreparedStatement ps = db.connection().prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(map(rs));
            } catch (SQLException e) { throw new RuntimeException(e); }
            return out;
        }
    }

    public void markHandled(long id, String handledBy) {
        synchronized (db) {
            String sql = "UPDATE reports SET handled=1, handled_by=? WHERE id=?";
            try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
                ps.setString(1, handledBy);
                ps.setLong(2, id);
                ps.executeUpdate();
            } catch (SQLException e) { throw new RuntimeException(e); }
        }
    }

    private Report map(ResultSet rs) throws SQLException {
        return new Report(rs.getLong("id"),
            UUID.fromString(rs.getString("reporter_uuid")), rs.getString("reporter_name"),
            UUID.fromString(rs.getString("target_uuid")), rs.getString("target_name"),
            rs.getString("reason"), rs.getLong("created_at"),
            rs.getInt("handled") == 1, rs.getString("handled_by"));
    }
}
