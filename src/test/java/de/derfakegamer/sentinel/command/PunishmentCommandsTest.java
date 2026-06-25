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

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

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

    /**
     * Pumps the scheduler enough to flush all async hops: DB executor callback → onMain task.
     * 200 iterations × 5 ms gives up to 1 second of wall time which is ample for the in-memory
     * SQLite executor.
     */
    private void drain() throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            server.getScheduler().performTicks(1);
            Thread.sleep(5);
        }
    }

    @Test
    void opCanBanTarget() throws Exception {
        Player target = server.addPlayer("Griefer");
        Player admin = server.addPlayer("Admin");
        admin.setOp(true);

        Command banCmd = server.getCommandMap().getCommand("ban");
        assertNotNull(banCmd, "ban command must be registered");

        new PunishmentCommands(plugin).onCommand(admin, banCmd, "ban",
                new String[]{"Griefer", "cheating"});

        drain();

        assertNotNull(plugin.punishments().activeBan(target.getUniqueId(), System.currentTimeMillis()).get(2, TimeUnit.SECONDS),
                "an active ban should exist after an op runs /ban");
    }

    @Test
    void nonOpCannotBanTarget() throws Exception {
        Player target = server.addPlayer("Victim");
        Player notOp = server.addPlayer("Regular");
        notOp.setOp(false);

        Command banCmd = server.getCommandMap().getCommand("ban");
        assertNotNull(banCmd, "ban command must be registered");

        new PunishmentCommands(plugin).onCommand(notOp, banCmd, "ban",
                new String[]{"Victim", "cheating"});

        drain();

        assertNull(plugin.punishments().activeBan(target.getUniqueId(), System.currentTimeMillis()).get(2, TimeUnit.SECONDS),
                "a non-op must not be able to record a ban");
    }

    @Test
    void ipbanOfOfflinePlayerRecordsNothing() throws Exception {
        Player admin = server.addPlayer("Admin");
        admin.setOp(true);

        String offlineName = "GhostPlayer";
        UUID offlineId = Bukkit.getOfflinePlayer(offlineName).getUniqueId();

        Command ipbanCmd = server.getCommandMap().getCommand("ipban");
        assertNotNull(ipbanCmd, "ipban command must be registered");

        boolean result = new PunishmentCommands(plugin).onCommand(admin, ipbanCmd, "ipban",
                new String[]{offlineName, "evading"});
        assertTrue(result, "command should return true");

        drain();

        List<Punishment> hist = plugin.punishments().history(offlineId).get(2, TimeUnit.SECONDS);
        for (Punishment p : hist) {
            assertNotEquals(PunishmentType.IPBAN, p.type(),
                    "an offline ipban must not create a stored IPBAN entry");
        }
        assertTrue(hist.isEmpty(),
                "no punishment should be recorded for an offline ipban target");
    }

    @Test
    void historyEmptyForNeverPunishedPlayer() throws Exception {
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
    void unknownOfflineNameIsRejected() throws Exception {
        Player admin = server.addPlayer("Admin");
        admin.setOp(true);

        Command banCmd = server.getCommandMap().getCommand("ban");
        assertNotNull(banCmd, "ban command must be registered");

        boolean handled = new PunishmentCommands(plugin).onCommand(admin, banCmd, "ban",
                new String[]{"NeverSeenXYZ", "spam"});
        assertTrue(handled);

        drain();

        List<Punishment> bans = plugin.punishments().activeList(
                PunishmentType.BAN, System.currentTimeMillis()).get(2, TimeUnit.SECONDS);
        assertTrue(bans.isEmpty(),
                "a never-seen name must not produce a phantom ban");
    }

    @Test
    void historyRendersAfterBan() throws Exception {
        Player target = server.addPlayer("Offender");
        Player admin = server.addPlayer("Admin");
        admin.setOp(true);

        Command banCmd = server.getCommandMap().getCommand("ban");
        new PunishmentCommands(plugin).onCommand(admin, banCmd, "ban",
                new String[]{"Offender", "griefing"});

        drain();

        List<Punishment> hist = plugin.punishments().history(target.getUniqueId()).get(2, TimeUnit.SECONDS);
        assertFalse(hist.isEmpty(),
                "history should contain the ban entry");

        Command historyCmd = server.getCommandMap().getCommand("history");
        assertNotNull(historyCmd, "history command must be registered");

        boolean result = new PunishmentCommands(plugin).onCommand(admin, historyCmd, "history",
                new String[]{"Offender"});
        assertTrue(result, "history command should return true on non-empty history");
    }
}
