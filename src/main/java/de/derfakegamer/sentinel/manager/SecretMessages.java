package de.derfakegamer.sentinel.manager;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import java.util.HashMap;
import java.util.Map;

/**
 * Internal, hard-coded message store. Unlike {@link de.derfakegamer.sentinel.util.Messages}
 * these templates are NOT read from messages.yml and NOT written to disk, so they leave no
 * trace in any server-visible file. Mirrors the {@code Messages} API.
 */
public final class SecretMessages {
    private final MiniMessage mm = MiniMessage.miniMessage();
    private final String prefix;
    private final Map<String, String> templates = new HashMap<>();

    public SecretMessages() { this(defaultPrefix()); }

    public SecretMessages(String prefix) {
        this.prefix = prefix == null ? defaultPrefix() : prefix;
        templates.put("gui-orbital-code-title", "<#3B82F6>Sentinel · Enter Code");
        templates.put("gui-orbital-mode-title", "<#3B82F6>Sentinel · Orbital Strike");
        templates.put("gui-orbital-payload-title", "<#3B82F6>Sentinel · Payload");
        templates.put("gui-orbital-dim-title", "<#3B82F6>Sentinel · Dimension");
        templates.put("orbital-wrong-code", "<red>Wrong code.");
        templates.put("orbital-rod-received", "<#60A5FA>Orbital strike rod received — right-click to fire.");
        templates.put("orbital-no-target", "<red>No target block in sight.");
        templates.put("orbital-fired", "<#60A5FA>Orbital strike inbound at <white><x>, <z></white>.");
        templates.put("orbital-world-gone", "<red>That world is no longer loaded.");
        templates.put("orbital-enter-coord", "<#60A5FA>Type the <white><axis></#60A5FA> coordinate in chat, or type <white>cancel<#60A5FA>.");
        templates.put("orbital-bad-coord", "<red>That's not a valid number.");
        templates.put("gui-owner-title", "<#3B82F6>Sentinel · Owner Panel");
        templates.put("gui-orbital-users-title", "<#3B82F6>Sentinel · Orbital Users");
        templates.put("owner-enter-user", "<#60A5FA>Type the player's name to allow, or type <white>cancel<#60A5FA>.");
        templates.put("owner-user-added", "<#60A5FA><player></#60A5FA> <gray>can now use the orbital strike.");
        templates.put("owner-user-removed", "<#60A5FA><player></#60A5FA> <gray>can no longer use the orbital strike.");
        templates.put("owner-enter-code", "<#60A5FA>Type the new 4-digit code, or type <white>cancel<#60A5FA>.");
        templates.put("owner-code-changed", "<#60A5FA>Orbital strike code updated.");
        templates.put("owner-bad-code", "<red>The code must be 4 digits.");
        templates.put("gui-orbital-when-title", "<#3B82F6>Sentinel · When");
        templates.put("orbital-enter-delay", "<#60A5FA>Type a delay (e.g. 10m, 2h), or type <white>cancel<#60A5FA>.");
        templates.put("orbital-scheduled", "<#60A5FA>Strike scheduled in <white><time></white>.");
        templates.put("gui-scheduled-title", "<#3B82F6>Sentinel · Scheduled Strikes");
        templates.put("scheduled-cancelled", "<#60A5FA>Scheduled strike cancelled.");
    }

    private static String defaultPrefix() {
        return "<#3B82F6>Sentinel <dark_gray>»</dark_gray> ";
    }

    private String raw(String key) { return templates.getOrDefault(key, key); }

    public Component prefixed(String key, String... placeholders) {
        return deserialize(prefix + raw(key), placeholders);
    }

    public Component plain(String key, String... placeholders) {
        return deserialize(raw(key), placeholders);
    }

    /**
     * Deserializes a MiniMessage template, but never throws: a single bad tag must not blow up
     * a command or listener. On any parse failure we fall back to the raw template with
     * placeholders substituted as plain text.
     */
    private Component deserialize(String template, String... placeholders) {
        try {
            return mm.deserialize(template, resolvers(placeholders));
        } catch (Throwable t) {
            return Component.text(substitute(template, placeholders));
        }
    }

    private static String substitute(String template, String... kv) {
        String out = template;
        for (int i = 0; i + 1 < kv.length; i += 2) {
            out = out.replace("<" + kv[i] + ">", kv[i + 1]);
        }
        return out;
    }

    private TagResolver[] resolvers(String... kv) {
        var out = new TagResolver[kv.length / 2];
        for (int i = 0; i < kv.length; i += 2) out[i / 2] = Placeholder.unparsed(kv[i], kv[i + 1]);
        return out;
    }
}
