package de.derfakegamer.sentinel.command;

import de.derfakegamer.sentinel.Sentinel;
import org.bukkit.command.TabCompleter;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Asserts that every command processed by Sentinel has a tab-completer registered,
 * and that the two most critical suggestion rules are wired correctly:
 *   1. A player-targeting command (/report) suggests online player names at arg 1.
 *   2. A temp-punishment command (/tempban) suggests durations at arg 2.
 *
 * We invoke the registered TabCompleter directly rather than routing through
 * MockBukkit's Command#tabComplete, because MockBukkit's default online-player
 * fallback can mask whether our custom completer ran.
 */
class CompletionWiringTest {

    ServerMock server;
    Sentinel plugin;

    @BeforeEach
    void setup() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(Sentinel.class);
    }

    @AfterEach
    void teardown() {
        MockBukkit.unmock();
    }

    /** Resolves the registered TabCompleter for a given command name and asserts it exists. */
    private TabCompleter completer(String name) {
        org.bukkit.command.PluginCommand cmd =
            (org.bukkit.command.PluginCommand) server.getCommandMap().getCommand(name);
        assertNotNull(cmd, "command /" + name + " must be registered");
        TabCompleter tc = cmd.getTabCompleter();
        assertNotNull(tc, "tab completer must be registered for /" + name);
        return tc;
    }

    private List<String> complete(String cmdName, PlayerMock sender, String[] args) {
        org.bukkit.command.PluginCommand cmd =
            (org.bukkit.command.PluginCommand) server.getCommandMap().getCommand(cmdName);
        TabCompleter tc = completer(cmdName);
        return tc.onTabComplete(sender, cmd, cmdName, args);
    }

    // -----------------------------------------------------------------------
    // Core wiring assertions: player names and durations
    // -----------------------------------------------------------------------

    @Test
    void reportSuggestsOnlinePlayerNameAtArg1() {
        PlayerMock op = server.addPlayer("Admin");
        op.setOp(true);
        server.addPlayer("Griefer");

        List<String> out = complete("report", op, new String[]{"Gr"});
        assertTrue(out.contains("Griefer"),
            "/report arg1 must suggest online player names");
    }

    @Test
    void tempbanSuggestsDurationsAtArg2() {
        PlayerMock op = server.addPlayer("Admin");
        op.setOp(true);

        List<String> out = complete("tempban", op, new String[]{"Griefer", "1"});
        assertTrue(out.stream().anyMatch(s -> s.startsWith("1")),
            "/tempban arg2 must suggest duration strings starting with the prefix");
    }

    // -----------------------------------------------------------------------
    // Wiring checks: every command has a registered tab-completer
    // -----------------------------------------------------------------------

    @Test void reportCompleterRegistered()      { completer("report"); }
    @Test void appealCompleterRegistered()      { completer("appeal"); }
    @Test void broadcastCompleterRegistered()   { completer("broadcast"); }
    @Test void clearchatCompleterRegistered()   { completer("clearchat"); }
    @Test void restartCompleterRegistered()     { completer("restart"); }
    @Test void playtimeCompleterRegistered()    { completer("playtime"); }
    @Test void backupCompleterRegistered()      { completer("backup"); }
    @Test void scCompleterRegistered()          { completer("sc"); }
    @Test void rulesCompleterRegistered()       { completer("rules"); }

    // -----------------------------------------------------------------------
    // Spot-check suggestion rules for subcommand commands
    // -----------------------------------------------------------------------

    @Test
    void restartSuggestsDurationsAndCancel() {
        PlayerMock op = server.addPlayer("Admin");
        op.setOp(true);

        List<String> out = complete("restart", op, new String[]{""});
        assertTrue(out.contains("cancel"),
            "/restart arg1 must suggest 'cancel'");
        assertFalse(out.isEmpty(),
            "/restart arg1 must return non-empty suggestions");
    }

    // -----------------------------------------------------------------------
    // No-op commands return empty list (not null, not the default player list)
    // -----------------------------------------------------------------------

    @Test
    void clearchatReturnsEmpty() {
        PlayerMock op = server.addPlayer("Admin");
        op.setOp(true);

        List<String> out = complete("clearchat", op, new String[]{""});
        assertNotNull(out);
        assertTrue(out.isEmpty(), "/clearchat must return an empty list");
    }

    @Test
    void backupReturnsEmpty() {
        PlayerMock op = server.addPlayer("Admin");
        op.setOp(true);

        List<String> out = complete("backup", op, new String[]{""});
        assertNotNull(out);
        assertTrue(out.isEmpty(), "/backup must return an empty list");
    }

    @Test
    void rulesReturnsEmpty() {
        PlayerMock op = server.addPlayer("Admin");
        op.setOp(true);

        List<String> out = complete("rules", op, new String[]{""});
        assertNotNull(out);
        assertTrue(out.isEmpty(), "/rules must return an empty list");
    }
}
