package de.derfakegamer.sentinel.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.configuration.file.FileConfiguration;

public final class Messages {
    private final MiniMessage mm = MiniMessage.miniMessage();
    private volatile FileConfiguration config;

    public Messages(FileConfiguration config) { this.config = config; }

    public void reload(FileConfiguration config) { this.config = config; }

    private String raw(String key) { return config.getString(key, key); }

    public String prefix() { return raw("prefix"); }

    public Component prefixed(String key, String... placeholders) {
        return deserialize(raw("prefix") + raw(key), placeholders);
    }

    public Component plain(String key, String... placeholders) {
        return deserialize(raw(key), placeholders);
    }

    /**
     * Deserializes a MiniMessage template, but never throws: a single bad tag in a config
     * value must not blow up a command or listener. On any parse failure we fall back to the
     * raw template with placeholders substituted as plain text.
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

    private net.kyori.adventure.text.minimessage.tag.resolver.TagResolver[] resolvers(String... kv) {
        var out = new net.kyori.adventure.text.minimessage.tag.resolver.TagResolver[kv.length / 2];
        for (int i = 0; i < kv.length; i += 2) out[i / 2] = Placeholder.unparsed(kv[i], kv[i + 1]);
        return out;
    }
}
