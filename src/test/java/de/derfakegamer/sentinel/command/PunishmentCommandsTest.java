package de.derfakegamer.sentinel.command;

import de.derfakegamer.sentinel.Sentinel;
import org.bukkit.command.Command;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

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
}
