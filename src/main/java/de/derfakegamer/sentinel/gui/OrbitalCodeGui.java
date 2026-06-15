package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.util.Items;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class OrbitalCodeGui extends Gui {
    // keypad: 1..9 in a 3x3, 0 below, clear next to it, display at slot 4
    private static final Map<Integer, Character> SLOT_DIGIT = new HashMap<>();
    static {
        int[] grid = {10,11,12, 19,20,21, 28,29,30};
        char[] d = {'1','2','3','4','5','6','7','8','9'};
        for (int i = 0; i < 9; i++) SLOT_DIGIT.put(grid[i], d[i]);
        SLOT_DIGIT.put(38, '0');
    }
    private static final int DISPLAY = 4, CLEAR = 39, CLOSE = 44;

    private final StringBuilder entered = new StringBuilder();

    public OrbitalCodeGui(Sentinel plugin) {
        super(plugin);
        this.inventory = Bukkit.createInventory(this, 45, plugin.messages().plain("gui-orbital-code-title"));
        render();
    }

    public int slotForDigit(char digit) {
        for (var e : SLOT_DIGIT.entrySet()) if (e.getValue() == digit) return e.getKey();
        return -1;
    }

    private void render() {
        for (var e : SLOT_DIGIT.entrySet())
            inventory.setItem(e.getKey(), Items.numberButton(e.getValue() - '0'));
        inventory.setItem(DISPLAY, Items.button(Material.NAME_TAG,
            Component.text("Code: " + "•".repeat(entered.length()) + "_".repeat(4 - entered.length()),
                NamedTextColor.WHITE), List.of()));
        inventory.setItem(CLEAR, Items.button(Material.BARRIER, Component.text("Clear", NamedTextColor.RED), List.of()));
        inventory.setItem(CLOSE, Items.button(Material.BARRIER, Component.text("Close", NamedTextColor.RED), List.of()));
        fillEmpty();
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        event.setCancelled(true);
        Player p = (Player) event.getWhoClicked();
        int slot = event.getRawSlot();
        if (slot == CLOSE) { p.closeInventory(); return; }
        if (slot == CLEAR) { entered.setLength(0); render(); return; }
        Character digit = SLOT_DIGIT.get(slot);
        if (digit == null) return;
        if (entered.length() < 4) entered.append(digit);
        render();
        if (entered.length() == 4) {
            if (entered.toString().equals(plugin.orbitalAccess().code())) {
                new OrbitalModeGui(plugin).open(p);
            } else {
                p.sendMessage(plugin.messages().prefixed("orbital-wrong-code"));
                entered.setLength(0);
                render();
            }
        }
    }
}
