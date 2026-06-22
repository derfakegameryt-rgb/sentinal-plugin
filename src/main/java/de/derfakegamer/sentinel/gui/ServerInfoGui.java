package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.util.Items;
import de.derfakegamer.sentinel.util.ServerOptimizer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.lang.management.ManagementFactory;
import java.util.List;

public final class ServerInfoGui extends Gui {
    private static final int BACK = 18, CLOSE = 26, OPTIMIZE = 22;
    private final ServerOptimizer optimizer;

    public ServerInfoGui(Sentinel plugin) {
        super(plugin);
        this.optimizer = new ServerOptimizer(plugin);
        this.inventory = Bukkit.createInventory(this, 27, plugin.messages().plain("gui-serverinfo-title"));

        // Static items (never change while the GUI is open).
        inventory.setItem(10, info(Material.BOOK, "gui.serverinfo.version", List.of(
            "MC/Paper: " + Bukkit.getBukkitVersion(),
            "Server: " + Bukkit.getVersion(),
            "Plugin: " + plugin.getPluginMeta().getVersion())));
        inventory.setItem(13, info(Material.COMPARATOR, "gui.serverinfo.system", List.of(
            "OS: " + System.getProperty("os.name") + " (" + System.getProperty("os.arch") + ")",
            "CPU cores: " + Runtime.getRuntime().availableProcessors(),
            "Java: " + System.getProperty("java.version"))));
        inventory.setItem(15, info(Material.GRASS_BLOCK, "gui.serverinfo.worlds", List.of(
            "Loaded: " + Bukkit.getWorlds().size())));

        update();

        inventory.setItem(BACK, Items.button(Material.OAK_DOOR, plugin.messages().plain("gui.serverinfo.back"), List.of()));
        inventory.setItem(CLOSE, Items.button(Material.BARRIER, plugin.messages().plain("gui.serverinfo.close"), List.of()));
        optimizeButton();
        border();
        fillEmpty();
    }

    /** (Re)writes the dynamic items: TPS, memory, players, uptime. Idempotent. */
    public void update() {
        Runtime rt = Runtime.getRuntime();
        long usedMb = (rt.totalMemory() - rt.freeMemory()) / 1048576L;
        long maxMb = rt.maxMemory() / 1048576L;

        inventory.setItem(11, info(Material.CLOCK, "gui.serverinfo.tps", List.of(tpsLine())));
        inventory.setItem(12, info(Material.IRON_BLOCK, "gui.serverinfo.memory", List.of(
            "Used: " + usedMb + " MB", "Max: " + maxMb + " MB")));
        inventory.setItem(14, info(Material.PLAYER_HEAD, "gui.serverinfo.players", List.of(
            "Online: " + Bukkit.getOnlinePlayers().size() + " / " + Bukkit.getMaxPlayers())));
        inventory.setItem(16, info(Material.SUNFLOWER, "gui.serverinfo.uptime", List.of(uptimeLine())));
    }

    @Override
    public void open(Player player) {
        super.open(player);
        new org.bukkit.scheduler.BukkitRunnable() {
            @Override public void run() {
                if (!(player.getOpenInventory().getTopInventory().getHolder() instanceof ServerInfoGui g) || g != ServerInfoGui.this) {
                    cancel(); return;            // closed or navigated away
                }
                update();
            }
        }.runTaskTimer(plugin, 20L, 20L);        // every 1 second
    }

    private String tpsLine() {
        try {
            double[] tps = Bukkit.getServer().getTPS();
            return String.format("1m %.1f · 5m %.1f · 15m %.1f", tps[0], tps[1], tps[2]);
        } catch (Throwable t) { return "n/a"; }
    }

    private String uptimeLine() {
        long ms = ManagementFactory.getRuntimeMXBean().getUptime();
        long s = ms / 1000, h = s / 3600, m = (s % 3600) / 60;
        return h + "h " + m + "m";
    }

    private org.bukkit.inventory.ItemStack info(Material m, String nameKey, List<String> lines) {
        java.util.List<Component> lore = new java.util.ArrayList<>();
        for (String l : lines) lore.add(Component.text(l, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        return Items.button(m, plugin.messages().plain(nameKey), lore);
    }

    private void optimizeButton() {
        ServerOptimizer.Preset rec = optimizer.recommended();
        int cv = optimizer.currentView(), cs = optimizer.currentSim();
        java.util.List<Component> lore = java.util.List.of(
            plugin.messages().plain("gui.serverinfo.optimize-current", "view", String.valueOf(cv), "sim", String.valueOf(cs)),
            plugin.messages().plain("gui.serverinfo.optimize-recommended",
                "ram", String.valueOf(optimizer.ramGb()), "view", String.valueOf(rec.view()), "sim", String.valueOf(rec.sim())),
            plugin.messages().plain("gui.serverinfo.optimize-hint"));
        inventory.setItem(OPTIMIZE, Items.button(Material.ANVIL, plugin.messages().plain("gui.serverinfo.optimize"), lore));
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        event.setCancelled(true);
        Player p = (Player) event.getWhoClicked();
        if (event.getRawSlot() == BACK) new AdminPanelGui(plugin).open(p);
        else if (event.getRawSlot() == CLOSE) p.closeInventory();
        else if (event.getRawSlot() == OPTIMIZE) {
            if (!plugin.staffPerms().canUse(p, "sentinel.use")) { p.sendMessage(plugin.messages().prefixed("no-permission")); return; }
            ServerOptimizer.Preset rec = optimizer.recommended();
            optimizer.apply(rec);
            plugin.audit().record(p.getName(), "OPTIMIZE", "server", "view=" + rec.view() + " sim=" + rec.sim());
            p.sendMessage(plugin.messages().prefixed("optimize-applied", "view", String.valueOf(rec.view()), "sim", String.valueOf(rec.sim())));
            optimizeButton(); // refresh lore — current now matches recommendation
        }
    }
}
