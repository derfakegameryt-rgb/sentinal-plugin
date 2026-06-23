package de.derfakegamer.sentinel.storage;

import de.derfakegamer.sentinel.model.ProfileOverride;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

public final class ProfileOverrideDao {
    private final Database db;

    public ProfileOverrideDao(Database db) { this.db = db; }

    public void upsert(ProfileOverride o) {
        String sql = """
            INSERT INTO profile_overrides (uuid,display_name,skin_value,skin_signature,updated_by,updated_at)
            VALUES (?,?,?,?,?,?)
            ON CONFLICT(uuid) DO UPDATE SET display_name=excluded.display_name,
                skin_value=excluded.skin_value, skin_signature=excluded.skin_signature,
                updated_by=excluded.updated_by, updated_at=excluded.updated_at""";
        try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
            ps.setString(1, o.uuid().toString());
            ps.setString(2, o.displayName());
            ps.setString(3, o.skinValue());
            ps.setString(4, o.skinSignature());
            ps.setString(5, o.updatedBy());
            ps.setLong(6, o.updatedAt());
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public ProfileOverride find(UUID uuid) {
        String sql = "SELECT * FROM profile_overrides WHERE uuid=?";
        try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new ProfileOverride(uuid, rs.getString("display_name"), rs.getString("skin_value"),
                    rs.getString("skin_signature"), rs.getString("updated_by"), rs.getLong("updated_at"));
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public void delete(UUID uuid) {
        try (PreparedStatement ps = db.connection().prepareStatement(
                "DELETE FROM profile_overrides WHERE uuid=?")) {
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }
}
