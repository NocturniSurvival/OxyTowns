package com.oxywire.oxytowns.command.commands.town.sub;

import cloud.commandframework.annotations.Argument;
import cloud.commandframework.annotations.CommandDescription;
import cloud.commandframework.annotations.CommandMethod;
import com.oxywire.oxytowns.cache.TownCache;
import com.oxywire.oxytowns.command.annotation.MustBeInTown;
import com.oxywire.oxytowns.command.annotation.SendersTown;
import com.oxywire.oxytowns.config.Messages;
import com.oxywire.oxytowns.entities.impl.town.Town;
import org.bukkit.entity.Player;

public final class DiscordCommand {

    private static final String DISCORD_WEBHOOK_PREFIX = "https://discord.com/api/webhooks/";
    private static final String DISCORD_WEBHOOK_PREFIX_ALT = "https://discordapp.com/api/webhooks/";

    private final TownCache townCache;

    public DiscordCommand(final TownCache townCache) {
        this.townCache = townCache;
    }

    @CommandMethod("town|t discord set <url>")
    @CommandDescription("Set a Discord webhook URL to relay town chat messages")
    @MustBeInTown
    public void onSet(final Player sender, final @SendersTown Town town, final @Argument("url") String url) {
        final Messages messages = Messages.get();

        if (!town.getOwner().equals(sender.getUniqueId())) {
            messages.getTown().getNotOwner().send(sender);
            return;
        }

        if (!isValidDiscordWebhookUrl(url)) {
            messages.getTown().getDiscord().getWebhookInvalidUrl().send(sender);
            return;
        }

        town.setDiscordWebhookUrl(url);
        messages.getTown().getDiscord().getWebhookSet().send(sender);
    }

    @CommandMethod("town|t discord remove")
    @CommandDescription("Remove the Discord webhook from your town")
    @MustBeInTown
    public void onRemove(final Player sender, final @SendersTown Town town) {
        final Messages messages = Messages.get();

        if (!town.getOwner().equals(sender.getUniqueId())) {
            messages.getTown().getNotOwner().send(sender);
            return;
        }

        if (town.getDiscordWebhookUrl() == null) {
            messages.getTown().getDiscord().getWebhookAlreadyRemoved().send(sender);
            return;
        }

        town.setDiscordWebhookUrl(null);
        messages.getTown().getDiscord().getWebhookRemoved().send(sender);
    }

    /**
     * Validates that the provided URL is a legitimate Discord webhook URL.
     * This prevents SSRF attacks where the server could be directed to make
     * HTTP requests to internal network addresses or arbitrary hosts.
     *
     * @param url the URL to validate
     * @return true if the URL is a valid Discord webhook URL
     */
    private static boolean isValidDiscordWebhookUrl(final String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        return url.startsWith(DISCORD_WEBHOOK_PREFIX) || url.startsWith(DISCORD_WEBHOOK_PREFIX_ALT);
    }
}
