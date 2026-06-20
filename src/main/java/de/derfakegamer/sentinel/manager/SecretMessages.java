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
