package de.derfakegamer.sentinel.storage;

import java.sql.*;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class OrbitalAllowDao {
    private final Database db;
    public OrbitalAllowDao(Database db) { this.db = db; }

    public void add(UUID uuid, String name) {
        synchronized (db) {
            String sql = "INSERT INTO orbital_allowed (uuid,name) VALUES (?,?) ON CONFLICT(uuid) DO UPDATE SET name=excluded.name";
            try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
                ps.setString(1, uuid.toString()); ps.setString(2, name); ps.executeUpdate();
            } catch (SQLException e) { throw new RuntimeException(e); }
        }
    }

    public void remove(UUID uuid) {
        synchronized (db) {
            try (PreparedStatement ps = db.connection().prepareStatement("DELETE FROM orbital_allowed WHERE uuid=?")) {
                ps.setString(1, uuid.toString()); ps.executeUpdate();
            } catch (SQLException e) { throw new RuntimeException(e); }
        }
    }

    public boolean contains(UUID uuid) {
        synchronized (db) {
            try (PreparedStatement ps = db.connection().prepareStatement("SELECT 1 FROM orbital_allowed WHERE uuid=?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
            } catch (SQLException e) { throw new RuntimeException(e); }
        }
    }

    /** uuid -> name, insertion order. */
    public Map<UUID, String> all() {
        synchronized (db) {
            Map<UUID, String> out = new LinkedHashMap<>();
            try (PreparedStatement ps = db.connection().prepareStatement("SELECT uuid,name FROM orbital_allowed");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.put(UUID.fromString(rs.getString("uuid")), rs.getString("name"));
            } catch (SQLException e) { throw new RuntimeException(e); }
            return out;
        }
    }
}
