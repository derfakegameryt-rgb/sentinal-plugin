package de.derfakegamer.sentinel.command;

import de.derfakegamer.sentinel.Sentinel;
import org.bukkit.command.Command;
import org.bukkit.command.TabCompleter;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class TabCompleteTest {
    ServerMock server; Sentinel plugin;

    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    /**
     * MockBukkit's {@link Command#tabComplete} does not reliably route to the registered
     * {@link TabCompleter} (without a completer it still returns default online-name
     * completions, masking whether our code ran). To assert our completion logic
     * meaningfully, invoke the registered completer directly.
     */
    private List<String> complete(String name, PlayerMock sender, String[] args) {
        org.bukkit.command.PluginCommand cmd =
            (org.bukkit.command.PluginCommand) server.getCommandMap().getCommand(name);
        TabCompleter completer = cmd.getTabCompleter();
        assertNotNull(completer, "tab completer registered for /" + name);
        return completer.onTabComplete(sender, cmd, name, args);
    }

    @Test void sentinelSuggestsSubcommands() {
        PlayerMock op = server.addPlayer("Admin"); op.setOp(true);
        List<String> out = complete("sentinel", op, new String[]{"re"});
        assertTrue(out.contains("reload"));
    }

    @Test void banSuggestsOnlinePlayerNames() {
        PlayerMock op = server.addPlayer("Admin"); op.setOp(true);
        server.addPlayer("Griefer");
        List<String> out = complete("ban", op, new String[]{"Gr"});
        assertTrue(out.contains("Griefer"));
    }

    @Test void tempbanSuggestsDurationsForSecondArg() {
        PlayerMock op = server.addPlayer("Admin"); op.setOp(true);
        List<String> out = complete("tempban", op, new String[]{"Griefer", "1"});
        assertTrue(out.stream().anyMatch(s -> s.startsWith("1")));
    }
}
