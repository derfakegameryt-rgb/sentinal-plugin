package de.derfakegamer.sentinel.storage;

import de.derfakegamer.sentinel.model.Punishment;
import de.derfakegamer.sentinel.model.PunishmentType;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class PunishmentDao {
    private final Database db;

    public PunishmentDao(Database db) { this.db = db; }

    public long insert(Punishment p) {
      {
        String sql = """
            INSERT INTO punishments
              (type,target_uuid,target_name,target_ip,reason,issuer_uuid,issuer_name,
               created_at,expires_at,active,removed_by,removed_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?)""";
        try (PreparedStatement ps = db.connection().prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, p.type().name());
            ps.setString(2, p.targetUuid().toString());
            ps.setString(3, p.targetName());
            ps.setString(4, p.targetIp());
            ps.setString(5, p.reason());
            ps.setString(6, p.issuerUuid().toString());
            ps.setString(7, p.issuerName());
            ps.setLong(8, p.createdAt());
            ps.setLong(9, p.expiresAt());
            ps.setInt(10, p.active() ? 1 : 0);
            ps.setString(11, p.removedBy());
            ps.setLong(12, p.removedAt());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : -1;
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
      }
    }

    public Punishment findActive(PunishmentType type, UUID target) {
      {
        String sql = "SELECT id,type,target_uuid,target_name,target_ip,reason,issuer_uuid,issuer_name,created_at,expires_at,active,removed_by,removed_at FROM punishments WHERE type=? AND target_uuid=? AND active=1 LIMIT 1";
        try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
            ps.setString(1, type.name());
            ps.setString(2, target.toString());
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? map(rs) : null; }
        } catch (SQLException e) { throw new RuntimeException(e); }
      }
    }

    public Punishment findActiveByIp(PunishmentType type, String ip) {
      {
        String sql = "SELECT * FROM punishments WHERE type=? AND target_ip=? AND active=1 LIMIT 1";
        try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
            ps.setString(1, type.name());
            ps.setString(2, ip);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? map(rs) : null; }
        } catch (SQLException e) { throw new RuntimeException(e); }
      }
    }

    public void deactivate(long id, String removedBy, long removedAt) {
      {
        String sql = "UPDATE punishments SET active=0, removed_by=?, removed_at=? WHERE id=?";
        try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
            ps.setString(1, removedBy);
            ps.setLong(2, removedAt);
            ps.setLong(3, id);
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
      }
    }

    public List<Punishment> findHistory(UUID target) {
      {
        String sql = "SELECT * FROM punishments WHERE target_uuid=? ORDER BY created_at DESC";
        List<Punishment> out = new ArrayList<>();
        try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
            ps.setString(1, target.toString());
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) out.add(map(rs)); }
        } catch (SQLException e) { throw new RuntimeException(e); }
        return out;
      }
    }

    public java.util.List<de.derfakegamer.sentinel.model.Punishment> findActiveByType(
            de.derfakegamer.sentinel.model.PunishmentType type) {
        {
            java.util.List<de.derfakegamer.sentinel.model.Punishment> out = new java.util.ArrayList<>();
            String sql = "SELECT id,type,target_uuid,target_name,target_ip,reason,issuer_uuid,issuer_name,created_at,expires_at,active,removed_by,removed_at FROM punishments WHERE type=? AND active=1 ORDER BY created_at DESC";
            try (java.sql.PreparedStatement ps = db.connection().prepareStatement(sql)) {
                ps.setString(1, type.name());
                try (java.sql.ResultSet rs = ps.executeQuery()) { while (rs.next()) out.add(map(rs)); }
            } catch (java.sql.SQLException e) { throw new RuntimeException(e); }
            return out;
        }
    }

    public int countWarns(UUID target) {
      {
        String sql = "SELECT COUNT(*) FROM punishments WHERE type='WARN' AND target_uuid=? AND active=1";
        try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
            ps.setString(1, target.toString());
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getInt(1) : 0; }
        } catch (SQLException e) { throw new RuntimeException(e); }
      }
    }

    private Punishment map(ResultSet rs) throws SQLException {
        return Punishment.builder()
            .id(rs.getLong("id"))
            .type(PunishmentType.valueOf(rs.getString("type")))
            .targetUuid(UUID.fromString(rs.getString("target_uuid")))
            .targetName(rs.getString("target_name"))
            .targetIp(rs.getString("target_ip"))
            .reason(rs.getString("reason"))
            .issuerUuid(UUID.fromString(rs.getString("issuer_uuid")))
            .issuerName(rs.getString("issuer_name"))
            .createdAt(rs.getLong("created_at"))
            .expiresAt(rs.getLong("expires_at"))
            .active(rs.getInt("active") == 1)
            .removedBy(rs.getString("removed_by"))
            .removedAt(rs.getLong("removed_at"))
            .build();
    }
}
