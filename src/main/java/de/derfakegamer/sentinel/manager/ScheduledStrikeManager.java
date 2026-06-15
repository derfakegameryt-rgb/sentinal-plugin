package de.derfakegamer.sentinel.manager;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.OrbitalPayload;
import de.derfakegamer.sentinel.model.ScheduledStrike;
import de.derfakegamer.sentinel.storage.ScheduledStrikeDao;
import org.bukkit.Bukkit;
import org.bukkit.World;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class ScheduledStrikeManager {
    private final Sentinel plugin;
    private final ScheduledStrikeDao dao;
    private final Map<Long, Integer> tasks = new HashMap<>();

    public ScheduledStrikeManager(Sentinel plugin, ScheduledStrikeDao dao) { this.plugin = plugin; this.dao = dao; }

    public long schedule(World world, int x, int z, OrbitalPayload payload, long fireAt) {
        long id = dao.insert(world.getName(), x, z, payload.name(), fireAt);
        arm(new ScheduledStrike(id, world.getName(), x, z, payload, fireAt));
        return id;
    }

    /** On enable: re-arm all persisted strikes (firing immediately any that are already due). */
    public void rearmAll() {
        for (ScheduledStrike s : dao.pending()) arm(s);
    }

    public List<ScheduledStrike> pending() { return dao.pending(); }

    public boolean cancel(long id) {
        Integer task = tasks.remove(id);
        if (task != null) Bukkit.getScheduler().cancelTask(task);
        return dao.delete(id) > 0;
    }

    private void arm(ScheduledStrike s) {
        long delayMs = s.fireAt() - System.currentTimeMillis();
        long delayTicks = Math.max(1, delayMs / 50);
        int task = Bukkit.getScheduler().runTaskLater(plugin, () -> fire(s), delayTicks).getTaskId();
        tasks.put(s.id(), task);
    }

    private void fire(ScheduledStrike s) {
        tasks.remove(s.id());
        World world = Bukkit.getWorld(s.world());
        if (world != null) plugin.orbital().strike(world, s.x(), s.z(), s.payload());
        dao.delete(s.id());
    }
}
