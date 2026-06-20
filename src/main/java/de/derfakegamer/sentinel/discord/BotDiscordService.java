package de.derfakegamer.sentinel.discord;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.PunishmentType;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.requests.GatewayIntent;

import java.awt.Color;
import java.util.EnumSet;
import java.util.logging.Level;

public final class BotDiscordService implements DiscordService {
    private final Sentinel plugin;
    private final String token, guildId, channelId;
    private volatile JDA jda;

    public BotDiscordService(Sentinel plugin) {
        this.plugin = plugin;
        this.token = plugin.getConfig().getString("discord.bot.token", "");
        this.guildId = plugin.getConfig().getString("discord.bot.guild-id", "");
        this.channelId = plugin.getConfig().getString("discord.bot.log-channel-id", "");
    }

    /** Connects to Discord. Call from an async task — awaitReady briefly blocks. Fail-soft. */
    public void start() {
        try {
            this.jda = JDABuilder.createLight(token, EnumSet.noneOf(GatewayIntent.class)).build();
            jda.awaitReady();
            plugin.getLogger().info("Sentinel: Discord bot connected as " + jda.getSelfUser().getAsTag());
            jda.addEventListener(new SlashCommandListener(plugin));
            net.dv8tion.jda.api.entities.Guild guild = jda.getGuildById(guildId);
            if (guild != null) {
                guild.updateCommands().addCommands(
                    net.dv8tion.jda.api.interactions.commands.build.Commands.slash("ban", "Ban a player")
                        .addOption(net.dv8tion.jda.api.interactions.commands.OptionType.STRING, "player", "player name", true)
                        .addOption(net.dv8tion.jda.api.interactions.commands.OptionType.STRING, "reason", "reason", true),
                    net.dv8tion.jda.api.interactions.commands.build.Commands.slash("tempban", "Temp-ban a player")
                        .addOption(net.dv8tion.jda.api.interactions.commands.OptionType.STRING, "player", "player name", true)
                        .addOption(net.dv8tion.jda.api.interactions.commands.OptionType.STRING, "duration", "e.g. 1d2h", true)
                        .addOption(net.dv8tion.jda.api.interactions.commands.OptionType.STRING, "reason", "reason", true),
                    net.dv8tion.jda.api.interactions.commands.build.Commands.slash("mute", "Mute a player")
                        .addOption(net.dv8tion.jda.api.interactions.commands.OptionType.STRING, "player", "player name", true)
                        .addOption(net.dv8tion.jda.api.interactions.commands.OptionType.STRING, "reason", "reason", true),
                    net.dv8tion.jda.api.interactions.commands.build.Commands.slash("tempmute", "Temp-mute a player")
                        .addOption(net.dv8tion.jda.api.interactions.commands.OptionType.STRING, "player", "player name", true)
                        .addOption(net.dv8tion.jda.api.interactions.commands.OptionType.STRING, "duration", "e.g. 1d2h", true)
                        .addOption(net.dv8tion.jda.api.interactions.commands.OptionType.STRING, "reason", "reason", true),
                    net.dv8tion.jda.api.interactions.commands.build.Commands.slash("kick", "Kick a player")
                        .addOption(net.dv8tion.jda.api.interactions.commands.OptionType.STRING, "player", "player name", true)
                        .addOption(net.dv8tion.jda.api.interactions.commands.OptionType.STRING, "reason", "reason", true),
                    net.dv8tion.jda.api.interactions.commands.build.Commands.slash("warn", "Warn a player")
                        .addOption(net.dv8tion.jda.api.interactions.commands.OptionType.STRING, "player", "player name", true)
                        .addOption(net.dv8tion.jda.api.interactions.commands.OptionType.STRING, "reason", "reason", true),
                    net.dv8tion.jda.api.interactions.commands.build.Commands.slash("unban", "Unban a player")
                        .addOption(net.dv8tion.jda.api.interactions.commands.OptionType.STRING, "player", "player name", true),
                    net.dv8tion.jda.api.interactions.commands.build.Commands.slash("unmute", "Unmute a player")
                        .addOption(net.dv8tion.jda.api.interactions.commands.OptionType.STRING, "player", "player name", true),
                    net.dv8tion.jda.api.interactions.commands.build.Commands.slash("stats", "Moderation statistics"),
                    net.dv8tion.jda.api.interactions.commands.build.Commands.slash("audit", "Recent audit for a player")
                        .addOption(net.dv8tion.jda.api.interactions.commands.OptionType.STRING, "player", "player name", true)
                ).queue();
            }
        } catch (Throwable t) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "Sentinel: Discord bot failed to start", t);
            this.jda = null;
        }
    }

    public JDA jda() { return jda; }
    public boolean isReady() { return jda != null && jda.getStatus() == JDA.Status.CONNECTED; }

    private void postEmbed(EmbedData data) {
        JDA j = jda;
        if (j == null || channelId.isBlank()) return;
        try {
            TextChannel ch = j.getTextChannelById(channelId);
            if (ch == null) return;
            EmbedBuilder b = new EmbedBuilder().setTitle(data.title()).setColor(new Color(data.color()));
            for (EmbedData.Field f : data.fields()) b.addField(f.name(), f.value(), false);
            ch.sendMessageEmbeds(b.build()).queue(null, err ->
                plugin.getLogger().fine("Discord embed failed: " + err));
        } catch (Throwable t) {
            plugin.getLogger().log(Level.FINE, "Discord embed failed", t);
        }
    }

    @Override public void logPunishment(PunishmentType type, String targetName, String issuerName, String reason, long expiresAt) {
        postEmbed(DiscordEmbeds.punishment(type, targetName, issuerName, reason, expiresAt));
    }
    @Override public void logReport(String reporterName, String targetName, String reason) {
        postEmbed(DiscordEmbeds.report(reporterName, targetName, reason));
    }
    @Override public void logAppeal(String targetName, PunishmentType type, String text) {
        postEmbed(DiscordEmbeds.appeal(targetName, type, text));
    }
    @Override public void updatePresence(int online, int max) {
        JDA j = jda;
        if (j == null) return;
        try {
            j.getPresence().setActivity(net.dv8tion.jda.api.entities.Activity.playing(
                StatusFormatter.format(plugin.getConfig().getString("discord.bot.status", "{online}/{max} online"), online, max)));
        } catch (Throwable t) { plugin.getLogger().fine("Discord presence failed: " + t.getMessage()); }
    }
    @Override public void shutdown() {
        JDA j = jda;
        if (j != null) try { j.shutdownNow(); } catch (Throwable ignored) {}
    }
}
