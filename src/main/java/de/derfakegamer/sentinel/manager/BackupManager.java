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
        plugin.scheduler().runGlobal(() -> {
            List<File> worldDirs = new ArrayList<>();
            for (org.bukkit.World w : Bukkit.getWorlds()) { w.save(); worldDirs.add(w.getWorldFolder()); }
            File dir = new File(plugin.getDataFolder(), "backups");
            dir.mkdirs();
            File zip = new File(dir, "backup-" + stamp + ".zip");
            int keep = plugin.getConfig().getInt("backup.keep", 5);
            plugin.scheduler().runAsync(() -> {
                try {
                    zipWorlds(worldDirs, zip);
                    prune(dir, keep);
                    plugin.scheduler().runGlobal(() ->
                        requester.sendMessage(plugin.messages().prefixed("backup-done", "file", zip.getName())));
                } catch (Exception e) {
                    plugin.scheduler().runGlobal(() ->
                        requester.sendMessage(plugin.messages().prefixed("backup-failed", "error", String.valueOf(e.getMessage()))));
                }
            });
        });
    }

    void zipWorlds(List<File> dirs, File zip) throws IOException {
        // Write to a sibling temp file, validate it, then atomically move it into place — a dropped
        // connection, a full disk, or a crash can never leave a truncated/corrupt backup behind.
        File part = new File(zip.getParentFile(), zip.getName() + ".part");
        try {
            try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(part)))) {
                for (File d : dirs) addDir(d.getParentFile().toPath(), d, zos);
            }
            validateZip(part);
            moveIntoPlace(part, zip);
        } finally {
            Files.deleteIfExists(part.toPath()); // no-op once the move succeeded
        }
    }

    /** Opens the archive to prove it is a complete, readable zip with at least one entry. */
    static void validateZip(File f) throws IOException {
        try (ZipFile zf = new ZipFile(f)) { // throws on a truncated/corrupt file
            Enumeration<? extends ZipEntry> en = zf.entries();
            int count = 0;
            while (en.hasMoreElements()) { en.nextElement(); count++; }
            if (count == 0) throw new IOException("backup archive has no entries: " + f.getName());
        }
    }

    /** Atomically replaces {@code dest} with {@code tmp}; falls back to a plain replace where an
     *  atomic move is unsupported (e.g. some Windows setups). */
    static void moveIntoPlace(File tmp, File dest) throws IOException {
        try {
            Files.move(tmp.toPath(), dest.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
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
        for (int i = 0; i < zips.length - keep; i++) {
            if (!zips[i].delete())
                plugin.getLogger().warning("Sentinel backup: could not delete old backup " + zips[i].getName());
        }
    }
}
