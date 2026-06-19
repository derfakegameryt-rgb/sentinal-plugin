package de.derfakegamer.sentinel.manager;

import de.derfakegamer.sentinel.Sentinel;

public final class MaintenanceManager {
    private final Sentinel plugin;
    public MaintenanceManager(Sentinel plugin) { this.plugin = plugin; }

    public boolean isEnabled() { return plugin.getConfig().getBoolean("maintenance.enabled", false); }

    public void setEnabled(boolean on) {
        plugin.getConfig().set("maintenance.enabled", on);
        plugin.saveConfig();
    }

    public String kickMessage() {
        return plugin.getConfig().getString("maintenance.kick-message", "The server is under maintenance.");
    }

    public String motd() { return plugin.getConfig().getString("maintenance.motd", "Under maintenance"); }
}
