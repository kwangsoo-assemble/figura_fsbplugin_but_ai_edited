package org.figuramc.figura.server.events.pings;

import org.figuramc.figura.server.FiguraUser;
import org.figuramc.figura.server.events.CancellableEvent;

/**
 * Fired when a client sends an avatar ping to the server, after rate limit checks
 * but before the ping is broadcast to other players.
 * Cancelling this event prevents the broadcast.
 */
public class PingReceivedEvent extends CancellableEvent {
    private final FiguraUser sender;
    private final int id;
    private final boolean sync;
    private final byte[] data;

    public PingReceivedEvent(FiguraUser sender, int id, boolean sync, byte[] data) {
        this.sender = sender;
        this.id = id;
        this.sync = sync;
        this.data = data;
    }

    public FiguraUser sender() {
        return sender;
    }

    public int id() {
        return id;
    }

    public boolean sync() {
        return sync;
    }

    public byte[] data() {
        return data;
    }
}
