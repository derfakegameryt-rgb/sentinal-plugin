package de.derfakegamer.sentinel.command;

import de.derfakegamer.sentinel.Sentinel;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/** Shows the server rules, read live from plugins/Sentinel/rules.txt (MiniMessage per line). */
public final class RulesCommand implements CommandExecutor {
    private final Sentinel plugin;
    public RulesCommand(Sentinel plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        File file = new File(plugin.getDataFolder(), "rules.txt");
        try {
            List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
            MiniMessage mm = MiniMessage.miniMessage();
            for (String line : lines) sender.sendMessage(mm.deserialize(line));
        } catch (Exception e) {
            sender.sendMessage(plugin.messages().prefixed("rules-missing"));
        }
        return true;
    }
}
