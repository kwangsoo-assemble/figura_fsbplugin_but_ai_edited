package org.figuramc.figura.server;

import org.figuramc.figura.server.packets.CustomFSBPacket;

import java.util.HashMap;
import java.util.UUID;

public class FiguraCustomPackets {
    private final HashMap<Integer, String> idMap = new HashMap<>();
    private final HashMap<String, CustomPacketListener> listeners = new HashMap<>();

    public interface CustomPacketListener {
        /**
         * @param packetName  name the packet was registered under
         * @param sender      user whose connection delivered the packet
         * @param avatarOwner owner of the avatar whose script sent the packet;
         *                    equals {@code sender.uuid()} for host avatar packets
         * @param data        raw payload
         */
        void dispatch(String packetName, FiguraUser sender, UUID avatarOwner, byte[] data);
    }

    /**
     * Registers a new listener for specified packet. Rewrites previous for specified packetName listener.
     * @param packetName name of packet to listen to
     * @param listener listener object
     */
    public void registerListener(String packetName, CustomPacketListener listener) {
        listeners.put(packetName, listener);
        idMap.put(packetName.hashCode(), packetName);
    }

    public void handlePacket(FiguraUser sender, CustomFSBPacket packet) {
        String packetName = idMap.get(packet.id());
        CustomPacketListener listener = listeners.get(packetName);
        if (listener != null) {
            listener.dispatch(packetName, sender, packet.avatarOwner(), packet.data());
        }
    }
}
