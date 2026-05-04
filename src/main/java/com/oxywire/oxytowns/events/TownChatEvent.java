package com.oxywire.oxytowns.events;

import com.oxywire.oxytowns.entities.impl.town.Town;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

public class TownChatEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Town town;
    private final Player sender;
    private final String message;
    private boolean cancelled;

    /**
     * Fired when a player sends a message in town chat.
     *
     * @param town    The town the message was sent in.
     * @param sender  The player who sent the message.
     * @param message The raw message content.
     */
    public TownChatEvent(final Town town, final Player sender, final String message) {
        this.town = town;
        this.sender = sender;
        this.message = message;
        this.cancelled = false;
    }

    public Town getTown() {
        return town;
    }

    public Player getSender() {
        return sender;
    }

    public String getMessage() {
        return message;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(final boolean cancelled) {
        this.cancelled = cancelled;
    }

    @NotNull
    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    @SuppressWarnings("unused")
    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
