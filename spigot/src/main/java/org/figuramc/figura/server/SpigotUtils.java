package org.figuramc.figura.server;

import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.Set;

public class SpigotUtils {
    // Spigot (and older Paper) expose CraftPlayer#addChannel/#removeChannel.
    // Modern Paper/Purpur (1.21.5+) removed them; the channel set moved to the connection
    // and is exposed through the public CraftPlayer#channels() accessor instead.
    private static Method addChannelMethod;
    private static Method removeChannelMethod;
    private static Method channelsMethod;
    private static boolean resolved;

    private static void resolve(Player player) {
        if (resolved) return;
        Class<?> clazz = player.getClass();
        try {
            addChannelMethod = clazz.getMethod("addChannel", String.class);
            removeChannelMethod = clazz.getMethod("removeChannel", String.class);
        } catch (NoSuchMethodException legacyMissing) {
            try {
                channelsMethod = clazz.getMethod("channels");
            } catch (NoSuchMethodException modernMissing) {
                // No known hook; the server will only be able to send to clients
                // that registered the channels themselves (minecraft:register).
            }
        }
        resolved = true;
    }

    public static void addChannel(Player player, String channel) {
        resolve(player);
        try {
            if (addChannelMethod != null) {
                addChannelMethod.invoke(player, channel);
            } else if (channelsMethod != null) {
                channels(player).add(channel);
            }
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    public static void removeChannel(Player player, String channel) {
        resolve(player);
        try {
            if (removeChannelMethod != null) {
                removeChannelMethod.invoke(player, channel);
            } else if (channelsMethod != null) {
                channels(player).remove(channel);
            }
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Set<String> channels(Player player) throws ReflectiveOperationException {
        return (Set<String>) channelsMethod.invoke(player);
    }
}
