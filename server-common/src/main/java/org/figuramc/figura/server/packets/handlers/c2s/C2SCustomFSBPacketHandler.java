package org.figuramc.figura.server.packets.handlers.c2s;

import org.figuramc.figura.server.FiguraServer;
import org.figuramc.figura.server.FiguraUser;
import org.figuramc.figura.server.events.Events;
import org.figuramc.figura.server.packets.CustomFSBPacket;
import org.figuramc.figura.server.utils.IFriendlyByteBuf;

public class C2SCustomFSBPacketHandler extends AuthorizedC2SPacketHandler<CustomFSBPacket> {

    public C2SCustomFSBPacketHandler(FiguraServer parent) {
        super(parent);
    }

    @Override
    protected void handle(FiguraUser sender, CustomFSBPacket packet) {
        // non-host avatar packets are only accepted when explicitly enabled in the config
        if (!packet.avatarOwner().equals(sender.uuid()) && !parent.config().allowNonHostPackets()) return;
        parent.customPackets().handlePacket(sender, packet);
    }

    @Override
    public CustomFSBPacket serialize(IFriendlyByteBuf byteBuf) {
        return new CustomFSBPacket(byteBuf);
    }
}
