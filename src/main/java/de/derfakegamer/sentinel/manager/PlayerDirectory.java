package de.derfakegamer.sentinel.manager;

import de.derfakegamer.sentinel.model.PlayerRecord;
import de.derfakegamer.sentinel.storage.PlayerDao;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class PlayerDirectory {
    private final PlayerDao dao;

    public PlayerDirectory(PlayerDao dao) { this.dao = dao; }

    public void record(UUID uuid, String name, String ip) {
        dao.upsert(uuid, name, ip, System.currentTimeMillis());
    }

    public PlayerRecord byUuid(UUID uuid) { return dao.byUuid(uuid); }

    public PlayerRecord byName(String name) { return dao.byName(name); }

    /** Other accounts that share this player's last IP. */
    public List<PlayerRecord> alts(UUID uuid) {
        PlayerRecord self = dao.byUuid(uuid);
        if (self == null || self.lastIp() == null) return List.of();
        List<PlayerRecord> out = new ArrayList<>();
        for (PlayerRecord r : dao.byIp(self.lastIp()))
            if (!r.uuid().equals(uuid)) out.add(r);
        return out;
    }
}
