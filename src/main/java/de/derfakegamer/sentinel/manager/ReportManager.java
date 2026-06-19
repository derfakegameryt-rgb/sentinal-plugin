package de.derfakegamer.sentinel.manager;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.Report;
import de.derfakegamer.sentinel.storage.ReportDao;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class ReportManager {
    private final Sentinel plugin;
    private final ReportDao dao;

    public ReportManager(Sentinel plugin, ReportDao dao) { this.plugin = plugin; this.dao = dao; }

    /** Files a report and alerts online staff. Future completes false if reporter == target. */
    public CompletableFuture<Boolean> file(CommandSender reporter, UUID targetId, String targetName, String reason) {
        UUID reporterId = (reporter instanceof Player p) ? p.getUniqueId() : new UUID(0, 0);
        if (reporterId.equals(targetId)) return CompletableFuture.completedFuture(false);
        long now = System.currentTimeMillis();
        CompletableFuture<Boolean> future = plugin.db().submit(() -> {
            dao.insert(new Report(0, reporterId, reporter.getName(), targetId, targetName,
                reason, now, false, null));
            return true;
        });
        plugin.db().callback(future, ok -> {
            if (Boolean.TRUE.equals(ok)) {
                plugin.discord().post(":triangular_flag_on_post: **" + reporter.getName() + "** reported **" + targetName + "**: " + reason);
                for (org.bukkit.entity.Player staff : Bukkit.getOnlinePlayers()) {
                    if (staff.isOp()) staff.sendMessage(plugin.messages().plain("report-alert",
                        "reporter", reporter.getName(), "player", targetName, "reason", reason));
                }
            }
        });
        return future;
    }

    public CompletableFuture<List<Report>> open() { return plugin.db().submit(() -> dao.findOpen()); }

    public void handle(long id, String staffName) { plugin.db().execute(() -> dao.markHandled(id, staffName)); }
}
