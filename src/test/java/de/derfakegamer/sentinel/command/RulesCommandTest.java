package de.derfakegamer.sentinel.command;

import de.derfakegamer.sentinel.Sentinel;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import static org.junit.jupiter.api.Assertions.*;

class RulesCommandTest {
    ServerMock server; Sentinel plugin;

    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    @Test void rulesSendsTheRulesFile() {
        PlayerMock p = server.addPlayer("Player");
        new RulesCommand(plugin).onCommand(p, server.getCommandMap().getCommand("rules"), "rules", new String[0]);
        // the default rules.txt was saved on enable, so the player receives at least one line
        assertNotNull(p.nextComponentMessage());
    }
}
