package de.derfakegamer.sentinel.command;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.Punishment;
import de.derfakegamer.sentinel.model.PunishmentType;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PunishmentCommandsTest {
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

    @Test
    void opCanBanTarget() {
        Player target = server.addPlayer("Griefer");
        Player admin = server.addPlayer("Admin");
        admin.setOp(true);

        Command banCmd = server.getCommandMap().getCommand("ban");
        assertNotNull(banCmd, "ban command must be registered");

        new PunishmentCommands(plugin).onCommand(admin, banCmd, "ban",
                new String[]{"Griefer", "cheating"});

        assertNotNull(plugin.punishments().activeBan(target.getUniqueId(), System.currentTimeMillis()),
                "an active ban should exist after an op runs /ban");
    }

    @Test
    void nonOpCannotBanTarget() {
        Player target = server.addPlayer("Victim");
        Player notOp = server.addPlayer("Regular");
        notOp.setOp(false);

        Command banCmd = server.getCommandMap().getCommand("ban");
        assertNotNull(banCmd, "ban command must be registered");

        new PunishmentCommands(plugin).onCommand(notOp, banCmd, "ban",
                new String[]{"Victim", "cheating"});

        assertNull(plugin.punishments().activeBan(target.getUniqueId(), System.currentTimeMillis()),
                "a non-op must not be able to record a ban");
    }

    @Test
    void ipbanOfOfflinePlayerRecordsNothing() {
        Player admin = server.addPlayer("Admin");
        admin.setOp(true);

        String offlineName = "GhostPlayer";
        UUID offlineId = Bukkit.getOfflinePlayer(offlineName).getUniqueId();

        Command ipbanCmd = server.getCommandMap().getCommand("ipban");
        assertNotNull(ipbanCmd, "ipban command must be registered");

        boolean result = new PunishmentCommands(plugin).onCommand(admin, ipbanCmd, "ipban",
                new String[]{offlineName, "evading"});
        assertTrue(result, "command should return true");

        for (Punishment p : plugin.punishments().history(offlineId)) {
            assertNotEquals(PunishmentType.IPBAN, p.type(),
                    "an offline ipban must not create a stored IPBAN entry");
        }
        assertTrue(plugin.punishments().history(offlineId).isEmpty(),
                "no punishment should be recorded for an offline ipban target");
    }

    @Test
    void historyEmptyForNeverPunishedPlayer() {
        Player admin = server.addPlayer("Admin");
        admin.setOp(true);
        server.addPlayer("Clean");

        Command historyCmd = server.getCommandMap().getCommand("history");
        assertNotNull(historyCmd, "history command must be registered");

        boolean result = new PunishmentCommands(plugin).onCommand(admin, historyCmd, "history",
                new String[]{"Clean"});
        assertTrue(result, "history command should return true on empty history");
    }

    @Test
    void historyRendersAfterBan() {
        Player target = server.addPlayer("Offender");
        Player admin = server.addPlayer("Admin");
        admin.setOp(true);

        Command banCmd = server.getCommandMap().getCommand("ban");
        new PunishmentCommands(plugin).onCommand(admin, banCmd, "ban",
                new String[]{"Offender", "griefing"});

        assertFalse(plugin.punishments().history(target.getUniqueId()).isEmpty(),
                "history should contain the ban entry");

        Command historyCmd = server.getCommandMap().getCommand("history");
        assertNotNull(historyCmd, "history command must be registered");

        boolean result = new PunishmentCommands(plugin).onCommand(admin, historyCmd, "history",
                new String[]{"Offender"});
        assertTrue(result, "history command should return true on non-empty history");
    }
}
