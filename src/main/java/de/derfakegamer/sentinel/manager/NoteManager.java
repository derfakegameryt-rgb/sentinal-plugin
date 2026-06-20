package de.derfakegamer.sentinel.manager;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.Note;
import de.derfakegamer.sentinel.storage.NoteDao;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class NoteManager {
    private final Sentinel plugin;
    private final NoteDao dao;

    public NoteManager(Sentinel plugin, NoteDao dao) {
        this.plugin = plugin;
        this.dao = dao;
    }

    public void add(UUID target, String author, String text) {
        long now = System.currentTimeMillis();
        plugin.db().execute(() -> dao.insert(new Note(0, target, author, text, now)));
    }

    public CompletableFuture<List<Note>> list(UUID target) {
        return plugin.db().submit(() -> dao.listFor(target));
    }
}
