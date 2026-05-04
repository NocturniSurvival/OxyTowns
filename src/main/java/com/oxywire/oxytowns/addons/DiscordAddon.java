package com.oxywire.oxytowns.addons;

import com.oxywire.oxytowns.OxyTownsPlugin;
import com.oxywire.oxytowns.config.Config;
import com.oxywire.oxytowns.events.TownChatEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.regex.Pattern;

public final class DiscordAddon implements Listener {

    /**
     * Matches @everyone, @here, and Discord user/role/channel mentions (<@123>, <@!123>, <#123>, <@&123>).
     * These are stripped from messages before sending to prevent unwanted pings.
     */
    private static final Pattern MENTION_PATTERN = Pattern.compile("@(everyone|here)|<(@[!&]?|#)\\d+>");

    /** Maximum messages buffered per webhook before new messages are dropped. */
    private static final int QUEUE_CAPACITY = 100;

    /** Interval between queue drain passes, in milliseconds. Conservative for Discord's rate limits. */
    private static final long DRAIN_INTERVAL_MS = 1_500;

    private final HttpClient httpClient;
    private final ScheduledExecutorService scheduler;

    /** Per-webhook-URL queue of JSON payloads ready to send. */
    private final ConcurrentHashMap<String, LinkedBlockingQueue<String>> webhookQueues = new ConcurrentHashMap<>();

    /**
     * Per-webhook-URL rate limit: epoch millis at which we are allowed to send again.
     * Updated whenever Discord returns a 429.
     */
    private final ConcurrentHashMap<String, Long> rateLimitUntil = new ConcurrentHashMap<>();

    public DiscordAddon() {
        OxyTownsPlugin.get().getServer().getPluginManager().registerEvents(this, OxyTownsPlugin.get());

        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            final Thread thread = new Thread(runnable, "OxyTowns-Discord-Worker");
            thread.setDaemon(true);
            return thread;
        });

        this.scheduler.scheduleAtFixedRate(this::drainQueues, DRAIN_INTERVAL_MS, DRAIN_INTERVAL_MS, TimeUnit.MILLISECONDS);

        OxyTownsPlugin.get().getLogger().info("Discord webhook addon loaded.");
    }

    @EventHandler
    public void onTownChat(final TownChatEvent event) {
        if (event.isCancelled()) return;

        final Config.DiscordWebhook config = Config.get().getDiscordWebhook();
        if (!config.isEnabled()) return;

        final String webhookUrl = event.getTown().getDiscordWebhookUrl();
        if (webhookUrl == null || webhookUrl.isBlank()) return;

        final String sanitizedMessage = sanitize(event.getMessage());
        final String content = config.getMessageFormat()
            .replace("<sender>", escapeDiscordMarkdown(event.getSender().getName()))
            .replace("<message>", sanitizedMessage);

        final String payload = buildJsonPayload(config.getUsername(), config.getAvatarUrl(), content);

        final LinkedBlockingQueue<String> queue = webhookQueues.computeIfAbsent(
            webhookUrl,
            k -> new LinkedBlockingQueue<>(QUEUE_CAPACITY)
        );

        if (!queue.offer(payload)) {
            OxyTownsPlugin.get().getLogger().warning(
                "[DiscordAddon] Webhook queue full for town '" + event.getTown().getName() + "', dropping message."
            );
        }
    }

    /**
     * Runs on the background thread every DRAIN_INTERVAL_MS.
     * Sends one queued message per webhook URL that is not currently rate-limited.
     */
    private void drainQueues() {
        for (final var entry : webhookQueues.entrySet()) {
            final String webhookUrl = entry.getKey();
            final LinkedBlockingQueue<String> queue = entry.getValue();

            if (queue.isEmpty()) continue;

            final long rateLimitedUntil = rateLimitUntil.getOrDefault(webhookUrl, 0L);
            if (System.currentTimeMillis() < rateLimitedUntil) continue;

            final String payload = queue.poll();
            if (payload == null) continue;

            sendAsync(webhookUrl, payload);
        }
    }

    private void sendAsync(final String webhookUrl, final String payload) {
        final HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(webhookUrl))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .timeout(Duration.ofSeconds(10))
            .build();

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenAccept(response -> handleResponse(webhookUrl, response))
            .exceptionally(throwable -> {
                OxyTownsPlugin.get().getLogger().log(
                    Level.WARNING,
                    "[DiscordAddon] Failed to deliver webhook message: " + throwable.getMessage()
                );
                return null;
            });
    }

    private void handleResponse(final String webhookUrl, final HttpResponse<String> response) {
        final int status = response.statusCode();

        if (status == 204 || status == 200) {
            // Success — nothing to do.
            return;
        }

        if (status == 429) {
            // Rate limited. Honor the retry_after value Discord provides.
            final long retryAfterMs = parseRetryAfterMs(response.body());
            rateLimitUntil.put(webhookUrl, System.currentTimeMillis() + retryAfterMs);
            OxyTownsPlugin.get().getLogger().warning(
                "[DiscordAddon] Rate limited by Discord. Pausing webhook for " + retryAfterMs + "ms."
            );
            return;
        }

        if (status == 404) {
            // Webhook has been deleted from Discord. Clear it from every town that uses it
            // so we stop hammering a dead endpoint.
            OxyTownsPlugin.get().getLogger().warning(
                "[DiscordAddon] Webhook returned 404 (deleted or invalid). Clearing it from any towns using this URL."
            );
            removeInvalidWebhook(webhookUrl);
            return;
        }

        OxyTownsPlugin.get().getLogger().warning(
            "[DiscordAddon] Unexpected response from Discord webhook: HTTP " + status + " — " + response.body()
        );
    }

    /**
     * Parses the retry_after field from Discord's 429 JSON response body.
     * Discord sends: {"retry_after": 1.234, "global": false, ...}
     * Falls back to a safe 5-second pause if parsing fails.
     */
    private static long parseRetryAfterMs(final String body) {
        try {
            final int index = body.indexOf("\"retry_after\":");
            if (index == -1) return 5_000L;

            final int valueStart = index + "\"retry_after\":".length();
            int valueEnd = valueStart;
            while (valueEnd < body.length() && (Character.isDigit(body.charAt(valueEnd)) || body.charAt(valueEnd) == '.')) {
                valueEnd++;
            }

            final double seconds = Double.parseDouble(body.substring(valueStart, valueEnd).trim());
            // Add a small buffer to avoid immediately hitting the limit again.
            return (long) (seconds * 1000) + 250;
        } catch (final Exception e) {
            return 5_000L;
        }
    }

    /**
     * Removes a webhook URL that Discord has confirmed no longer exists.
     * Clears it from any town currently storing it, and removes the queue.
     */
    private void removeInvalidWebhook(final String webhookUrl) {
        OxyTownsPlugin.get().getTownCache().getTowns().forEach(town -> {
            if (webhookUrl.equals(town.getDiscordWebhookUrl())) {
                town.setDiscordWebhookUrl(null);
                OxyTownsPlugin.get().getLogger().info(
                    "[DiscordAddon] Removed invalid webhook from town '" + town.getName() + "'."
                );
            }
        });
        webhookQueues.remove(webhookUrl);
        rateLimitUntil.remove(webhookUrl);
    }

    /**
     * Strips @everyone, @here, and user/role/channel mentions from a message
     * to prevent Discord notification abuse via town chat.
     */
    private static String sanitize(final String message) {
        return MENTION_PATTERN.matcher(message).replaceAll("[mention removed]");
    }

    /**
     * Escapes Discord markdown characters from a string so that usernames
     * with underscores or asterisks don't accidentally trigger formatting.
     */
    private static String escapeDiscordMarkdown(final String text) {
        return text.replace("\\", "\\\\")
            .replace("*", "\\*")
            .replace("_", "\\_")
            .replace("~", "\\~")
            .replace("`", "\\`")
            .replace("|", "\\|");
    }

    /**
     * Builds a minimal Discord webhook JSON payload.
     * Avoids pulling in a JSON library by constructing it manually — the fields
     * are simple strings with no nesting, so this is safe and dependency-free.
     */
    private static String buildJsonPayload(final String username, final String avatarUrl, final String content) {
        final StringBuilder json = new StringBuilder("{");
        json.append("\"username\":").append(jsonString(username)).append(",");
        json.append("\"content\":").append(jsonString(content));

        if (avatarUrl != null && !avatarUrl.isBlank()) {
            json.append(",\"avatar_url\":").append(jsonString(avatarUrl));
        }

        json.append("}");
        return json.toString();
    }

    /**
     * Wraps a string in JSON quotes, escaping the minimal set of characters
     * required by JSON spec (backslash and double-quote).
     */
    private static String jsonString(final String value) {
        return "\"" + value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            + "\"";
    }

    /**
     * Called on plugin disable to cleanly shut down the background thread.
     */
    public void shutdown() {
        this.scheduler.shutdownNow();
    }
}
