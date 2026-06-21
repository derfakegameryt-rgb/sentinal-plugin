package de.derfakegamer.sentinel.storage;

import java.sql.*;

public final class SettingsDao {
    private final Database db;
    public SettingsDao(Database db) { this.db = db; }

    public String get(String key, String def) {
        {
            try (PreparedStatement ps = db.connection().prepareStatement("SELECT value FROM settings WHERE key=?")) {
                ps.setString(1, key);
                try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getString(1) : def; }
            } catch (SQLException e) { throw new RuntimeException(e); }
        }
    }

    public void set(String key, String value) {
        {
            String sql = db.dialect().settingsUpsert();
            try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
                ps.setString(1, key); ps.setString(2, value); ps.executeUpdate();
            } catch (SQLException e) { throw new RuntimeException(e); }
        }
    }
}
