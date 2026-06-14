package de.derfakegamer.sentinel.manager;

import de.derfakegamer.sentinel.model.Note;
import de.derfakegamer.sentinel.storage.NoteDao;

import java.util.List;
import java.util.UUID;

public final class NoteManager {
    private final NoteDao dao;

    public NoteManager(NoteDao dao) { this.dao = dao; }

    public void add(UUID target, String author, String text) {
        dao.insert(new Note(0, target, author, text, System.currentTimeMillis()));
    }

    public List<Note> list(UUID target) { return dao.listFor(target); }
}
