package de.derfakegamer.sentinel.manager;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.Report;
import de.derfakegamer.sentinel.storage.ReportDao;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

public final class ReportManager {
    private final Sentinel plugin;
    private final ReportDao dao;

    public ReportManager(Sentinel plugin, ReportDao dao) { this.plugin = plugin; this.dao = dao; }

    /** Files a report and alerts online staff. Returns false if reporter == target. */
    public boolean file(CommandSender reporter, UUID targetId, String targetName, String reason) {
        UUID reporterId = (reporter instanceof Player p) ? p.getUniqueId() : new UUID(0, 0);
        if (reporterId.equals(targetId)) return false;
        dao.insert(new Report(0, reporterId, reporter.getName(), targetId, targetName,
            reason, System.currentTimeMillis(), false, null));
        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (staff.isOp()) staff.sendMessage(plugin.messages().plain("report-alert",
                "reporter", reporter.getName(), "player", targetName, "reason", reason));
        }
        return true;
    }

    public List<Report> open() { return dao.findOpen(); }

    public void handle(long id, String staffName) { dao.markHandled(id, staffName); }
}
