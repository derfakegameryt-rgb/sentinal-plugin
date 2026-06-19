package de.derfakegamer.sentinel.command;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

/**
 * Dynamically-registered command for the orbital strike. Registered via the command map
 * at runtime instead of plugin.yml so it leaves no trace in any server-visible file.
 * Delegates to {@link OrbitalStrikeCommand}, which keeps the unchanged isAllowed check.
 */
public final class OrbitalBukkitCommand extends Command {
    private final OrbitalStrikeCommand delegate;

    public OrbitalBukkitCommand(OrbitalStrikeCommand delegate) {
        super("orbitalstrike");
        this.delegate = delegate;
        setPermission("sentinel.orbital");
        setDescription("");
    }

    @Override
    public boolean execute(@NotNull CommandSender sender, @NotNull String label, @NotNull String[] args) {
        return delegate.onCommand(sender, this, label, args);
    }
}
