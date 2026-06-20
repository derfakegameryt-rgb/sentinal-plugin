package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.PunishmentType;
import de.derfakegamer.sentinel.util.DurationParser;
import de.derfakegamer.sentinel.util.Items;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.util.List;

public final class TemplatesGui extends Gui {
    private static final int PAGE_SIZE = 45;
    private static final int BACK = 45, CLOSE = 53;

    private final OfflinePlayer target;
    private final List<String> templates;

    public TemplatesGui(Sentinel plugin, OfflinePlayer target) {
        super(plugin);
        this.target = target;
        this.templates = plugin.getConfig().getStringList("templates");
        this.inventory = Bukkit.createInventory(this, 54, plugin.messages().plain("gui-templates-title"));
        for (int i = 0; i < PAGE_SIZE && i < templates.size(); i++) {
            inventory.setItem(i, Items.button(Material.WRITABLE_BOOK,
                Component.text(templates.get(i), NamedTextColor.AQUA),
                List.of(Component.text("Click to apply", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false))));
        }
        inventory.setItem(BACK, Items.button(Material.ARROW, Component.text("Back", NamedTextColor.GRAY), List.of()));
        inventory.setItem(CLOSE, Items.button(Material.BARRIER, Component.text("Close", NamedTextColor.RED), List.of()));
        fillEmpty();
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        event.setCancelled(true);
        Player mod = (Player) event.getWhoClicked();
        int slot = event.getRawSlot();
        if (slot == BACK) { PlayerActionsGui.open(plugin, target, mod); return; }
        if (slot == CLOSE) { mod.closeInventory(); return; }
        if (slot < 0 || slot >= PAGE_SIZE || slot >= templates.size()) return;
        apply(mod, templates.get(slot));
        mod.closeInventory();
    }

    private void apply(Player mod, String spec) {
        String[] parts = spec.trim().split("\\s+");
        String word = parts[0].toLowerCase();
        PunishmentType type; long expiresAt = 0; int reasonFrom = 1;
        switch (word) {
            case "ban" -> type = PunishmentType.BAN;
            case "mute" -> type = PunishmentType.MUTE;
            case "kick" -> type = PunishmentType.KICK;
            case "warn" -> type = PunishmentType.WARN;
            case "tempban", "tempmute" -> {
                type = word.equals("tempban") ? PunishmentType.BAN : PunishmentType.MUTE;
                if (parts.length < 2) return;
                try { expiresAt = System.currentTimeMillis() + DurationParser.parse(parts[1]); }
                catch (IllegalArgumentException e) { return; }
                reasonFrom = 2;
            }
            default -> { return; }
        }
        String reason = reasonFrom >= parts.length ? "" : String.join(" ", java.util.Arrays.copyOfRange(parts, reasonFrom, parts.length));
        if (!plugin.staffPerms().canPerform(mod, type)) { mod.sendMessage(plugin.messages().prefixed("no-permission")); return; }
        plugin.db().callback(plugin.moderation().apply(mod.getUniqueId(), mod.getName(), target.getUniqueId(),
            target.getName() == null ? "?" : target.getName(),
            target.getPlayer() != null && target.getPlayer().getAddress() != null
                ? target.getPlayer().getAddress().getAddress().getHostAddress() : null,
            type, expiresAt, reason), ignored -> {});
    }
}
