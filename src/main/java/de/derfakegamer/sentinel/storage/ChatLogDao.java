package de.derfakegamer.sentinel.storage;

import de.derfakegamer.sentinel.model.ChatLogEntry;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class ChatLogDao {
    private final Database db;

    public ChatLogDao(Database db) { this.db = db; }

    public void log(UUID uuid, String name, String kind, String text, long now) {
        synchronized (db) {
            String sql = "INSERT INTO chatlog (uuid,name,kind,text,created_at) VALUES (?,?,?,?,?)";
            try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                ps.setString(2, name);
                ps.setString(3, kind);
                ps.setString(4, text);
                ps.setLong(5, now);
                ps.executeUpdate();
            } catch (SQLException e) { throw new RuntimeException(e); }
        }
    }

    /** Deletes log rows created before {@code cutoff} (epoch millis). Returns rows removed. */
    public int deleteOlderThan(long cutoff) {
        synchronized (db) {
            try (PreparedStatement ps = db.connection().prepareStatement("DELETE FROM chatlog WHERE created_at < ?")) {
                ps.setLong(1, cutoff);
                return ps.executeUpdate();
            } catch (SQLException e) { throw new RuntimeException(e); }
        }
    }

    public List<ChatLogEntry> recent(UUID uuid, int limit) {
        synchronized (db) {
            List<ChatLogEntry> out = new ArrayList<>();
            String sql = "SELECT * FROM chatlog WHERE uuid=? ORDER BY created_at DESC, id DESC LIMIT ?";
            try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                ps.setInt(2, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(new ChatLogEntry(rs.getLong("id"),
                        UUID.fromString(rs.getString("uuid")), rs.getString("name"),
                        rs.getString("kind"), rs.getString("text"), rs.getLong("created_at")));
                }
            } catch (SQLException e) { throw new RuntimeException(e); }
            return out;
        }
    }
}
