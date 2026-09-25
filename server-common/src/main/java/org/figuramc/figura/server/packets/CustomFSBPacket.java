package org.figuramc.figura.server.packets;

import org.figuramc.figura.server.utils.IFriendlyByteBuf;
import org.figuramc.figura.server.utils.Identifier;

import java.util.UUID;

public class CustomFSBPacket implements Packet {
    public static final Identifier PACKET_ID = new Identifier("figura", "ping/server");
    public static final int MAX_SERVER_PING_SIZE = 32766 - 20;

    /**
     * Owner of the avatar this packet is bound to.
     * <p>
     * C2S: uuid of the player whose avatar script sent this packet (not necessarily the connection owner).
     * S2C: uuid of the player whose loaded avatar should receive this packet on the client.
     */
    private final UUID avatarOwner;
    private final int id;
    private final byte[] data;

    public CustomFSBPacket(UUID avatarOwner, int id, byte[] data) {
        if (data.length > MAX_SERVER_PING_SIZE) throw new IllegalArgumentException("Server ping size can't be more than %s".formatted(MAX_SERVER_PING_SIZE));
        this.avatarOwner = avatarOwner;
        this.id = id;
        this.data = data;
    }

    public CustomFSBPacket(IFriendlyByteBuf buf) {
        avatarOwner = buf.readUUID();
        id = buf.readInt();
        data = buf.readBytes();
    }

    @Override
    public void write(IFriendlyByteBuf buf) {
        buf.writeUUID(avatarOwner);
        buf.writeInt(id);
        buf.writeBytes(data);
    }

    public UUID avatarOwner() {
        return avatarOwner;
    }

    public int id() {
        return id;
    }

    public byte[] data() {
        return data;
    }

    @Override
    public Identifier getId() {
        return PACKET_ID;
    }
}
