package de.derfakegamer.sentinel.manager;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.OrbitalPayload;
import de.derfakegamer.sentinel.model.ScheduledStrike;
import de.derfakegamer.sentinel.storage.ScheduledStrikeDao;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ScheduledStrikeManager {
    private final Sentinel plugin;
    private final ScheduledStrikeDao dao;
    private final Map<Long, List<Integer>> tasks = new ConcurrentHashMap<>();

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
        cancelTasks(id);
        return dao.delete(id) > 0;
    }

    private void arm(ScheduledStrike s) {
        long now = System.currentTimeMillis();
        long delayTicks = Math.max(1, (s.fireAt() - now) / 50);
        List<Integer> ids = new ArrayList<>();
        ids.add(Bukkit.getScheduler().runTaskLater(plugin, () -> fire(s), delayTicks).getTaskId());

        // live countdown over the final 10 seconds (10, 9, … 1) shown to everyone who may strike
        long startTicks = Math.max(0, (s.fireAt() - 10_000 - now) / 50);
        BukkitRunnable countdown = new BukkitRunnable() {
            @Override public void run() {
                long rem = s.fireAt() - System.currentTimeMillis();
                int secs = (int) Math.round(rem / 1000.0);
                if (secs <= 0) { cancel(); return; }
                if (secs <= 10) announceCountdown(secs);
            }
        };
        ids.add(countdown.runTaskTimer(plugin, startTicks, 20L).getTaskId());

        tasks.put(s.id(), ids);
    }

    private void fire(ScheduledStrike s) {
        cancelTasks(s.id());
        World world = Bukkit.getWorld(s.world());
        if (world != null) plugin.orbital().strike(world, s.x(), s.z(), s.payload());
        dao.delete(s.id());
    }

    private void cancelTasks(long id) {
        List<Integer> ids = tasks.remove(id);
        if (ids != null) for (int t : ids) Bukkit.getScheduler().cancelTask(t);
    }

    private void announceCountdown(int secs) {
        Title title = Title.title(
            Component.text(String.valueOf(secs), NamedTextColor.RED),
            Component.text("Orbital strike incoming", NamedTextColor.DARK_RED),
            Title.Times.times(Duration.ZERO, Duration.ofMillis(1100), Duration.ofMillis(150)));
        for (Player p : Bukkit.getOnlinePlayers())
            if (plugin.orbitalAccess().isAllowed(p)) p.showTitle(title);
    }
}
