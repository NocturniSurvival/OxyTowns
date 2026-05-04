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
 * injects one of two boolean contexts depending on the player's relationship to the
 * town chunk they are currently standing in:
 *
 * <ul>
 *   <li>{@code oxytowns:resident=true} — player is inside a town they own or are a member of</li>
 *   <li>{@code oxytowns:intruder=true} — player is inside a town they have no membership in</li>
 * </ul>
 *
 * Neither context is present while a player is in unclaimed wilderness.
 *
 * <p>Example LuckPerms usage:
 * <pre>
 *   /lp user Steve permission set some.permission false oxytowns:intruder=true
 *   /lp user Steve permission set some.permission true oxytowns:resident=true
 * </pre>
 *
 * <p>Contexts are refreshed automatically when a player crosses a chunk boundary.
 */
public final class LuckPermsAddon implements ContextCalculator<Player>, Listener {

    /** Injected when the player is standing inside a town they own or are a member of. */
    public static final String CONTEXT_RESIDENT = "oxytowns:resident";

    /** Injected when the player is standing inside a town they have no membership in. */
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
     * Exactly one of the two contexts is injected when the player is in claimed land;
     * neither is injected in wilderness.
     */
    @Override
    public void calculate(@NonNull final Player target, @NonNull final ContextConsumer consumer) {
        final var town = OxyTownsPlugin.get().getTownCache().getTownByLocation(target.getLocation());
        if (town == null) return;

        if (town.isMemberOrOwner(target.getUniqueId())) {
            consumer.accept(CONTEXT_RESIDENT, "true");
        } else {
            consumer.accept(CONTEXT_INTRUDER, "true");
        }
    }

    /**
     * Advertises the two possible contexts this calculator can produce so LuckPerms
     * can use them for cache invalidation and tab-completion.
     */
    @Override
    public @NonNull ContextSet estimatePotentialContexts() {
        return ImmutableContextSet.builder()
            .add(CONTEXT_RESIDENT, "true")
            .add(CONTEXT_INTRUDER, "true")
            .build();
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
