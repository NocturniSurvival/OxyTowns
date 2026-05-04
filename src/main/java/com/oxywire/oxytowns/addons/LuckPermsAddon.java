package com.oxywire.oxytowns.addons;

import com.oxywire.oxytowns.OxyTownsPlugin;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.context.ContextCalculator;
import net.luckperms.api.context.ContextConsumer;
import net.luckperms.api.context.ContextSet;
import net.luckperms.api.context.ImmutableContextSet;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.logging.Level;

/**
 * Integrates OxyTowns with LuckPerms by registering a {@link ContextCalculator} that
 * injects the context {@code oxytowns:intruder=<TownName>} whenever a player is standing
 * inside a town they are not a member or owner of.
 *
 * <p>Example LuckPerms usage:
 * <pre>
 *   /lp user Steve permission set some.permission true oxytowns:intruder=Riverdale
 * </pre>
 * This grants {@code some.permission} to Steve only while he is intruding on Riverdale.
 *
 * <p>Context values are refreshed automatically when a player crosses a chunk boundary.
 */
public final class LuckPermsAddon implements ContextCalculator<Player>, Listener {

    /**
     * The LuckPerms context key injected when a player is intruding on a foreign town.
     * The value is the name of the town being intruded upon.
     */
    public static final String CONTEXT_INTRUDER = "oxytowns:intruder";

    private final LuckPerms luckPerms;

    public LuckPermsAddon() {
        this.luckPerms = LuckPermsProvider.get();
        this.luckPerms.getContextManager().registerCalculator(this);
        OxyTownsPlugin.get().getServer().getPluginManager().registerEvents(this, OxyTownsPlugin.get());
        OxyTownsPlugin.get().getLogger().log(Level.INFO, "LuckPerms hooked!");
    }

    /**
     * Called by LuckPerms whenever it needs to resolve contexts for a player.
     * Injects {@code oxytowns:intruder=<TownName>} if the player is in a town
     * they don't belong to.
     */
    @Override
    public void calculate(@NonNull final Player target, @NonNull final ContextConsumer consumer) {
        final var town = OxyTownsPlugin.get().getTownCache().getTownByLocation(target.getLocation());
        if (town == null) return;

        if (!town.isMemberOrOwner(target.getUniqueId())) {
            consumer.accept(CONTEXT_INTRUDER, town.getName());
        }
    }

    /**
     * Returns an empty set — the intruder context is entirely dynamic and cannot
     * be enumerated ahead of time.
     */
    @Override
    public @NonNull ContextSet estimatePotentialContexts() {
        return ImmutableContextSet.empty();
    }

    /**
     * Signals LuckPerms to re-evaluate contexts for a player whenever they cross
     * a chunk boundary, keeping the intruder context in sync with their position.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(final PlayerMoveEvent event) {
        final var from = event.getFrom();
        final var to = event.getTo();
        if ((from.getBlockX() >> 4) == (to.getBlockX() >> 4)
                && (from.getBlockZ() >> 4) == (to.getBlockZ() >> 4)) {
            return;
        }
        this.luckPerms.getContextManager().signalContextUpdate(event.getPlayer());
    }
}
