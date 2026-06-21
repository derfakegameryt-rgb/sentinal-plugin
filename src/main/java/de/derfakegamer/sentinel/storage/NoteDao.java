package de.derfakegamer.sentinel.storage;

import de.derfakegamer.sentinel.model.Note;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class NoteDao {
    private final Database db;

    public NoteDao(Database db) { this.db = db; }

    public long insert(Note n) {
        {
            String sql = "INSERT INTO notes (target_uuid,author,text,created_at) VALUES (?,?,?,?)";
            try (PreparedStatement ps = db.connection().prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, n.targetUuid().toString());
                ps.setString(2, n.author());
                ps.setString(3, n.text());
                ps.setLong(4, n.createdAt());
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) { return keys.next() ? keys.getLong(1) : -1; }
            } catch (SQLException e) { throw new RuntimeException(e); }
        }
    }

    public List<Note> listFor(UUID target) {
        {
            List<Note> out = new ArrayList<>();
            try (PreparedStatement ps = db.connection().prepareStatement(
                    "SELECT * FROM notes WHERE target_uuid=? ORDER BY created_at DESC")) {
                ps.setString(1, target.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(new Note(rs.getLong("id"),
                        UUID.fromString(rs.getString("target_uuid")), rs.getString("author"),
                        rs.getString("text"), rs.getLong("created_at")));
                }
            } catch (SQLException e) { throw new RuntimeException(e); }
            return out;
        }
    }
}
