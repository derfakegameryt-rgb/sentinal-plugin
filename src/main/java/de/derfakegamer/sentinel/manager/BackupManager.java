package de.derfakegamer.sentinel.manager;

import de.derfakegamer.sentinel.Sentinel;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Saves the worlds and zips them into the backups/ folder, pruning to the newest N. */
public final class BackupManager {
    private final Sentinel plugin;
    public BackupManager(Sentinel plugin) { this.plugin = plugin; }

    /** Saves all worlds (sync), then zips + prunes async, reporting to the requester. {@code stamp} names the file. */
    public void backup(CommandSender requester, long stamp) {
        requester.sendMessage(plugin.messages().prefixed("backup-started"));
        List<File> worldDirs = new ArrayList<>();
        for (org.bukkit.World w : Bukkit.getWorlds()) { w.save(); worldDirs.add(w.getWorldFolder()); }
        File dir = new File(plugin.getDataFolder(), "backups");
        dir.mkdirs();
        File zip = new File(dir, "backup-" + stamp + ".zip");
        int keep = plugin.getConfig().getInt("backup.keep", 5);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                zipWorlds(worldDirs, zip);
                prune(dir, keep);
                Bukkit.getScheduler().runTask(plugin, () ->
                    requester.sendMessage(plugin.messages().prefixed("backup-done", "file", zip.getName())));
            } catch (Exception e) {
                Bukkit.getScheduler().runTask(plugin, () ->
                    requester.sendMessage(plugin.messages().prefixed("backup-failed", "error", String.valueOf(e.getMessage()))));
            }
        });
    }

    void zipWorlds(List<File> dirs, File zip) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(zip)))) {
            for (File d : dirs) addDir(d.getParentFile().toPath(), d, zos);
        }
    }

    private void addDir(Path root, File f, ZipOutputStream zos) throws IOException {
        File[] kids = f.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            if (k.getName().equals("session.lock")) continue; // locked while server runs
            if (k.isDirectory()) { addDir(root, k, zos); continue; }
            zos.putNextEntry(new ZipEntry(root.relativize(k.toPath()).toString().replace('\\', '/')));
            Files.copy(k.toPath(), zos);
            zos.closeEntry();
        }
    }

    /** Keeps the newest {@code keep} backup zips, deletes the rest. */
    void prune(File dir, int keep) {
        File[] zips = dir.listFiles((d, n) -> n.startsWith("backup-") && n.endsWith(".zip"));
        if (zips == null || zips.length <= keep) return;
        Arrays.sort(zips, Comparator.comparingLong(File::lastModified));
        for (int i = 0; i < zips.length - keep; i++) zips[i].delete();
    }
}
