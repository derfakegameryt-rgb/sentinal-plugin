package de.derfakegamer.sentinel.discord;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.PunishmentType;
import de.derfakegamer.sentinel.util.DurationParser;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public final class SlashCommandListener extends ListenerAdapter {
    private static final UUID DISCORD_ISSUER = new UUID(0L, 0L);
    private final Sentinel plugin;

    public SlashCommandListener(Sentinel plugin) {
        this.plugin = plugin;
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent e) {
        List<String> staffRoleIds = plugin.getConfig().getStringList("discord.bot.staff-role-ids");
        Set<String> memberRoles = e.getMember() == null
            ? Set.of()
            : e.getMember().getRoles().stream().map(r -> r.getId()).collect(Collectors.toSet());
        if (!SlashAuth.mayModerate(memberRoles, staffRoleIds)) {
            e.reply("You are not allowed to use this.").setEphemeral(true).queue();
            return;
        }
        String cmd = e.getName();
        String playerName = e.getOption("player") == null ? null : e.getOption("player").getAsString();
        if (playerName == null) { e.reply("Missing player.").setEphemeral(true).queue(); return; }
        String reason = e.getOption("reason") == null ? "" : e.getOption("reason").getAsString();
        String durationStr = e.getOption("duration") == null ? null : e.getOption("duration").getAsString();
        String issuer = "Discord: " + e.getUser().getName();

        e.deferReply(true).queue(); // ack within 3s; ephemeral so only invoker sees outcome
        // resolve the target off the JDA thread via the DB executor
        plugin.players().byName(playerName).whenComplete((rec, err) -> {
            if (err != null) { e.getHook().sendMessage("Database error, please try again.").queue(); return; }
            if (rec == null) { e.getHook().sendMessage("Player not found: " + playerName).queue(); return; }
            UUID target = rec.uuid();
            long now = System.currentTimeMillis();
            switch (cmd) {
                case "ban"  -> apply(e, PunishmentType.BAN,  target, rec.name(), rec.lastIp(), issuer, reason, 0);
                case "mute" -> apply(e, PunishmentType.MUTE, target, rec.name(), rec.lastIp(), issuer, reason, 0);
                case "kick" -> apply(e, PunishmentType.KICK, target, rec.name(), rec.lastIp(), issuer, reason, 0);
                case "warn" -> apply(e, PunishmentType.WARN, target, rec.name(), rec.lastIp(), issuer, reason, 0);
                case "tempban", "tempmute" -> {
                    long expires;
                    try { expires = now + DurationParser.parse(durationStr); }
                    catch (RuntimeException ex) { e.getHook().sendMessage("Bad duration: " + durationStr).queue(); return; }
                    apply(e, cmd.equals("tempban") ? PunishmentType.BAN : PunishmentType.MUTE,
                        target, rec.name(), rec.lastIp(), issuer, reason, expires);
                }
                case "unban" -> plugin.db().callback(
                    plugin.moderation().removeBan(DISCORD_ISSUER, issuer, target, rec.name()),
                    ok -> e.getHook().sendMessage(
                        Boolean.TRUE.equals(ok) ? "Unbanned " + rec.name() : rec.name() + " was not banned.").queue());
                case "unmute" -> plugin.db().callback(
                    plugin.moderation().removeMute(DISCORD_ISSUER, issuer, target, rec.name()),
                    ok -> e.getHook().sendMessage(
                        Boolean.TRUE.equals(ok) ? "Unmuted " + rec.name() : rec.name() + " was not muted.").queue());
                default -> e.getHook().sendMessage("Unknown command.").queue();
            }
        });
    }

    private void apply(SlashCommandInteractionEvent e, PunishmentType type, UUID target, String targetName,
                       String ip, String issuer, String reason, long expiresAt) {
        plugin.db().callback(
            plugin.moderation().apply(DISCORD_ISSUER, issuer, target, targetName, ip, type, expiresAt, reason),
            ok -> e.getHook().sendMessage(Boolean.TRUE.equals(ok)
                ? type.name().toLowerCase() + " applied to " + targetName
                : targetName + " is exempt or the action did nothing.").queue());
    }
}
