package org.figuramc.figura.server.api;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.figuramc.figura.server.FiguraServer;
import org.figuramc.figura.server.FiguraUser;
import org.figuramc.figura.server.packets.CustomFSBPacket;
import org.figuramc.figura.server.packets.s2c.S2CPingPacket;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Public entry point for other plugins to interact with FiguraFSB.
 * <p>
 * All methods are safe to call from the main server thread. Methods silently do nothing
 * when FSB is not initialized or the target player has no active FSB session.
 */
public final class FiguraFSBApi {
    private FiguraFSBApi() {
    }

    private static @Nullable FiguraServer server() {
        return FiguraServer.initialized() ? FiguraServer.getInstance() : null;
    }

    /**
     * @return true if the player has completed the FSB handshake (i.e. runs a Figura client
     * with FSB support connected to this server).
     */
    public static boolean isConnected(UUID player) {
        FiguraServer srv = server();
        if (srv == null) return false;
        FiguraUser user = srv.userManager().getUserOrNull(player);
        return user != null && user.online();
    }

    /**
     * Sends an avatar ping to a single receiver, as if it was sent by {@code avatarOwner}.
     * The receiver's client runs ping function {@code pingId} of {@code avatarOwner}'s avatar.
     *
     * @param avatarOwner player whose avatar defines the ping function
     * @param pingId      Figura's internal ping function id (see FSBPingEvent#getPingId
     *                    for observed values)
     * @param data        raw ping payload in Figura's ping serialization format
     * @param receiver    player to deliver the ping to
     */
    public static void sendPing(UUID avatarOwner, int pingId, byte[] data, Player receiver) {
        FiguraServer srv = server();
        if (srv == null) return;
        srv.sendPacket(receiver.getUniqueId(), new S2CPingPacket(avatarOwner, pingId, data));
    }

    /**
     * Sends an avatar ping to every player with an active FSB session, including
     * {@code avatarOwner} themselves if connected.
     */
    public static void broadcastPing(UUID avatarOwner, int pingId, byte[] data) {
        FiguraServer srv = server();
        if (srv == null) return;
        srv.userManager().forEachUser(user -> user.sendPacket(new S2CPingPacket(avatarOwner, pingId, data)));
    }

    /**
     * Sends a named server packet to the receiver's own (host) avatar. The avatar script
     * receives it through its {@code server_packets} listener registered under the same name:
     * <pre>{@code server_packets["my_plugin:my_packet"] = function(buffer) ... end}</pre>
     *
     * @param receiver   player whose host avatar should receive the packet
     * @param packetName listener name agreed with the avatar script
     * @param data       payload, at most {@link CustomFSBPacket#MAX_SERVER_PING_SIZE} bytes
     */
    public static void sendServerPacket(Player receiver, String packetName, byte[] data) {
        sendServerPacket(receiver, receiver.getUniqueId(), packetName, data);
    }

    /**
     * Sends a named server packet to a specific avatar loaded on the receiver's client.
     * The listener registered under {@code packetName} in {@code avatarOwner}'s avatar script
     * (as seen by {@code receiver}) is invoked.
     * <p>
     * When {@code avatarOwner} differs from the receiver, the client must run a Figura build
     * with non-host server packet support; also enable {@code allowNonHostPackets} in the
     * FSB config if the same avatar is expected to answer back.
     *
     * @param receiver    player whose client should receive the packet
     * @param avatarOwner owner of the loaded avatar the packet is routed to
     * @param packetName  listener name agreed with the avatar script
     * @param data        payload, at most {@link CustomFSBPacket#MAX_SERVER_PING_SIZE} bytes
     */
    public static void sendServerPacket(Player receiver, UUID avatarOwner, String packetName, byte[] data) {
        FiguraServer srv = server();
        if (srv == null) return;
        FiguraUser user = srv.userManager().getUserOrNull(receiver.getUniqueId());
        if (user == null || !user.online()) return;
        user.sendFSBPacket(avatarOwner, packetName, data);
    }

    /**
     * Registers a listener for named server packets sent by avatar scripts via
     * {@code server_packets:sendPacket(name, buffer)}.
     * <p>
     * The listener receives both the sending player (connection) and the avatar owner the
     * packet claims to come from. For host avatar packets they are equal; they can only differ
     * when {@code allowNonHostPackets} is enabled in the FSB config.
     * <p>
     * Only one listener may exist per name across all plugins; registering again with
     * the same name replaces the previous listener. Namespace your packet names
     * (e.g. {@code "myplugin:something"}).
     */
    public static void onServerPacket(String packetName, ServerPacketListener listener) {
        FiguraServer srv = server();
        if (srv == null) return;
        srv.customPackets().registerListener(packetName, (name, sender, avatarOwner, data) ->
                listener.receive(name, sender.uuid(), Bukkit.getPlayer(sender.uuid()), avatarOwner, data));
    }

    /**
     * Reloads the FSB config from disk ({@code plugins/FiguraFSB/config.json}).
     * Handshake-bound values (ping limits, avatar size limits) apply to players
     * that connect after the reload; already connected clients keep their handshake values.
     */
    public static void reloadConfig() {
        FiguraServer srv = server();
        if (srv == null) return;
        srv.reloadConfig();
    }

    @FunctionalInterface
    public interface ServerPacketListener {
        /**
         * @param packetName  name the packet was registered under
         * @param senderUuid  UUID of the sending player (connection owner)
         * @param sender      the sending player, or null if they went offline mid-handling
         * @param avatarOwner owner of the avatar whose script sent the packet; equals
         *                    {@code senderUuid} unless non-host packets are enabled
         * @param data        raw payload written by the avatar script
         */
        void receive(String packetName, UUID senderUuid, @Nullable Player sender, UUID avatarOwner, byte[] data);
    }
}
