package org.figuramc.figura.server.api;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Bukkit event fired when a Figura client sends an avatar ping through FSB.
 * <p>
 * Fired on the main server thread, after FSB rate limit checks and before the ping
 * is broadcast to other players. Cancelling this event prevents the broadcast
 * (the sender is not notified).
 */
public class FSBPingEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID senderUuid;
    private final Player sender;
    private final int pingId;
    private final boolean sync;
    private final byte[] data;
    private boolean cancelled;

    public FSBPingEvent(UUID senderUuid, Player sender, int pingId, boolean sync, byte[] data) {
        this.senderUuid = senderUuid;
        this.sender = sender;
        this.pingId = pingId;
        this.sync = sync;
        this.data = data;
    }

    /**
     * UUID of the player whose avatar sent this ping.
     */
    public @NotNull UUID getSenderUuid() {
        return senderUuid;
    }

    /**
     * The online player who sent this ping, or null if they disconnected mid-handling.
     */
    public @Nullable Player getSender() {
        return sender;
    }

    /**
     * Figura's internal ping function id of the sender's avatar.
     */
    public int getPingId() {
        return pingId;
    }

    /**
     * Whether the sender requested the ping to also run on their own client (pings:... sync flag).
     */
    public boolean isSync() {
        return sync;
    }

    /**
     * Raw ping payload as serialized by the Figura client. Do not modify.
     */
    public byte[] getData() {
        return data;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }
}
