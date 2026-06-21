package de.derfakegamer.sentinel.util;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import java.util.Collection;
import java.util.List;

/** Case-insensitive, sorted prefix filters for command tab-completion. All fail-soft. */
public final class Completions {
    private Completions() {}
    private static final List<String> DURATIONS = List.of("1h", "6h", "12h", "1d", "7d", "30d", "perm");

    public static List<String> filter(String prefix, Collection<String> options) {
        String p = prefix == null ? "" : prefix.toLowerCase();
        return options.stream().filter(o -> o != null && o.toLowerCase().startsWith(p)).sorted().toList();
    }
    public static List<String> of(String prefix, String... options) { return filter(prefix, List.of(options)); }
    public static List<String> players(String prefix) {
        return filter(prefix, Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());
    }
    public static List<String> durations(String prefix) { return filter(prefix, DURATIONS); }
    public static List<String> reasons(String prefix, List<String> presets) { return filter(prefix, presets); }
}
