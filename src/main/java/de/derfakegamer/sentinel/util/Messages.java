package de.derfakegamer.sentinel.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.configuration.file.FileConfiguration;

public final class Messages {
    private final MiniMessage mm = MiniMessage.miniMessage();
    private FileConfiguration config;

    public Messages(FileConfiguration config) { this.config = config; }

    public void reload(FileConfiguration config) { this.config = config; }

    private String raw(String key) { return config.getString(key, key); }

    public Component prefixed(String key, String... placeholders) {
        return mm.deserialize(raw("prefix") + raw(key), resolvers(placeholders));
    }

    public Component plain(String key, String... placeholders) {
        return mm.deserialize(raw(key), resolvers(placeholders));
    }

    private net.kyori.adventure.text.minimessage.tag.resolver.TagResolver[] resolvers(String... kv) {
        var out = new net.kyori.adventure.text.minimessage.tag.resolver.TagResolver[kv.length / 2];
        for (int i = 0; i < kv.length; i += 2) out[i / 2] = Placeholder.unparsed(kv[i], kv[i + 1]);
        return out;
    }
}
