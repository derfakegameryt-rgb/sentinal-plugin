package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.util.Items;
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
    private static final int BACK = 18, CLOSE = 26;

    public ServerInfoGui(Sentinel plugin) {
        super(plugin);
        this.inventory = Bukkit.createInventory(this, 27, plugin.messages().plain("gui-serverinfo-title"));

        // Static items (never change while the GUI is open).
        inventory.setItem(10, info(Material.BOOK, "Version", List.of(
            "MC/Paper: " + Bukkit.getBukkitVersion(),
            "Server: " + Bukkit.getVersion(),
            "Plugin: " + plugin.getPluginMeta().getVersion())));
        inventory.setItem(13, info(Material.COMPARATOR, "System", List.of(
            "OS: " + System.getProperty("os.name") + " (" + System.getProperty("os.arch") + ")",
            "CPU cores: " + Runtime.getRuntime().availableProcessors(),
            "Java: " + System.getProperty("java.version"))));
        inventory.setItem(15, info(Material.GRASS_BLOCK, "Worlds", List.of(
            "Loaded: " + Bukkit.getWorlds().size())));

        update();

        inventory.setItem(BACK, Items.button(Material.OAK_DOOR, Component.text("Back", NamedTextColor.GRAY), List.of()));
        inventory.setItem(CLOSE, Items.button(Material.BARRIER, Component.text("Close", NamedTextColor.RED), List.of()));
        border();
        fillEmpty();
    }

    /** (Re)writes the dynamic items: TPS, memory, players, uptime. Idempotent. */
    public void update() {
        Runtime rt = Runtime.getRuntime();
        long usedMb = (rt.totalMemory() - rt.freeMemory()) / 1048576L;
        long maxMb = rt.maxMemory() / 1048576L;

        inventory.setItem(11, info(Material.CLOCK, "TPS", List.of(tpsLine())));
        inventory.setItem(12, info(Material.IRON_BLOCK, "Memory", List.of(
            "Used: " + usedMb + " MB", "Max: " + maxMb + " MB")));
        inventory.setItem(14, info(Material.PLAYER_HEAD, "Players", List.of(
            "Online: " + Bukkit.getOnlinePlayers().size() + " / " + Bukkit.getMaxPlayers())));
        inventory.setItem(16, info(Material.SUNFLOWER, "Uptime", List.of(uptimeLine())));
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

    private org.bukkit.inventory.ItemStack info(Material m, String title, List<String> lines) {
        java.util.List<Component> lore = new java.util.ArrayList<>();
        for (String l : lines) lore.add(Component.text(l, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        return Items.button(m, Component.text(title, NamedTextColor.AQUA), lore);
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        event.setCancelled(true);
        Player p = (Player) event.getWhoClicked();
        if (event.getRawSlot() == BACK) new AdminPanelGui(plugin).open(p);
        else if (event.getRawSlot() == CLOSE) p.closeInventory();
    }
}
