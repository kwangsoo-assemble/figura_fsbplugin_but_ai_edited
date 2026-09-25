package org.figuramc.figura.server;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.tree.CommandNode;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.chat.BaseComponentSerializer;
import net.md_5.bungee.chat.ComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.figuramc.figura.server.api.FSBPingEvent;
import org.figuramc.figura.server.commands.FiguraServerCommandSource;
import org.figuramc.figura.server.commands.FiguraServerCommands;
import org.figuramc.figura.server.events.Events;
import org.figuramc.figura.server.events.pings.PingReceivedEvent;
import org.figuramc.figura.server.packets.Packets;
import org.figuramc.figura.server.packets.Side;
import org.figuramc.figura.server.utils.ComponentUtils;
import org.figuramc.figura.server.utils.Identifier;

import java.util.*;
import java.util.concurrent.CompletableFuture;

public class FiguraSpigot extends JavaPlugin implements Listener {
    private FiguraServerSpigot srv;
    private BukkitTask tickTask;
    public static final boolean DEBUG = Objects.equals(System.getProperty("figuraDebug"), "true");
    private final ArrayList<Identifier> outcomingPackets = new ArrayList<>();

    private final CommandDispatcher<FiguraServerCommandSource> dispatcher = new CommandDispatcher<>();

    @Override
    public void onEnable() {
        srv = new FiguraServerSpigot(this);
        var msg = getServer().getMessenger();
        Bukkit.getPluginManager().registerEvents(this, this);
        Packets.forEachPacket(((id, packetDescriptor) -> {
            Side side = packetDescriptor.side();
            if (side.sentBy(Side.SERVER)) {
                msg.registerOutgoingPluginChannel(this, id.toString());
                outcomingPackets.add(id);
            }
            if (side.sentBy(Side.CLIENT)) msg.registerIncomingPluginChannel(this, id.toString(), srv);
            srv.logDebug("Registered channel for %s".formatted(id));
        }));
        srv.init();
        tickTask = new BukkitTickRunnable().runTaskTimer(this, 0, 1);

        dispatcher.register(FiguraServerCommands.getCommand());

        // Bridge FSB ping events to Bukkit events so other plugins can observe/cancel them.
        Events.registerHandler(PingReceivedEvent.class, event -> {
            Player player = Bukkit.getPlayer(event.sender().uuid());
            FSBPingEvent bukkitEvent = new FSBPingEvent(event.sender().uuid(), player, event.id(), event.sync(), event.data());
            Bukkit.getPluginManager().callEvent(bukkitEvent);
            if (bukkitEvent.isCancelled()) event.cancel();
        });

        resumeOnlinePlayers();
    }

    /**
     * Re-attaches players that were already online when this plugin got enabled.
     * <p>
     * This is the {@code /reload} (or plugin-manager reload) path. {@link #onDisable} wipes the
     * whole user map ({@code FiguraUserManager#close}), and {@link PlayerJoinEvent} does not fire
     * again for players who never left, so without this every one of them stays unknown to the
     * plugin. Everything routed through the user map then fails silently — notably
     * {@code FiguraFSBApi#sendServerPacket}, which drops the packet when the user is missing
     * or offline, so CommandHelper's {@code fsb_send_packet} would go nowhere until the player
     * reconnected.
     * <p>
     * Marking them online restores the server to client direction immediately. The unsolicited
     * handshake then tells the client to resynchronise; clients that accept it recover without
     * reconnecting, and older clients simply ignore it (no worse than before this call existed).
     */
    private void resumeOnlinePlayers() {
        var players = Bukkit.getOnlinePlayers();
        if (players.isEmpty()) return;
        for (Player player : players) {
            for (Identifier id: outcomingPackets) SpigotUtils.addChannel(player, id.toString());
            UUID uuid = player.getUniqueId();
            var user = srv.userManager().setupOnlinePlayer(uuid);
            user.sendPacket(srv.getHandshake(uuid));
        }
        srv.logInfo("Resumed %d player(s) after reload".formatted(players.size()));
    }

    private String[] getCmd(String commandName, String[] commandArgs) {
        String[] cmdName = new String[1 + commandArgs.length];
        cmdName[0] = commandName;
        System.arraycopy(commandArgs, 0, cmdName, 1, commandArgs.length);
        return cmdName;
    }

    @SuppressWarnings("NullableProblems")
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (sender instanceof Player pl) {
            FiguraSpigotCommandSource source = new FiguraSpigotCommandSource(pl.getUniqueId());
            String[] commandComponents = getCmd(label, args);
            String commandText = String.join(" ", commandComponents);
            ParseResults<FiguraServerCommandSource> parseResult = dispatcher.parse(commandText, source);
            try {
                dispatcher.execute(parseResult);
            } catch (CommandSyntaxException e) {
                sender.spigot().sendMessage(getSyntaxError(e));
            }
        }
        return true;
    }

    @SuppressWarnings("NullableProblems")
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (sender instanceof Player pl) {
            FiguraSpigotCommandSource source = new FiguraSpigotCommandSource(pl.getUniqueId());
            String[] commandComponents = getCmd(alias, args);
            String commandText = String.join(" ", commandComponents);
            ParseResults<FiguraServerCommandSource> parseResult = dispatcher.parse(commandText, source);
            Suggestions suggestions = dispatcher.getCompletionSuggestions(parseResult).join();
            return suggestions.getList().stream().map(Suggestion::getText).toList();
        }
        return List.of();
    }

    @Override
    public void onDisable() {
        srv.close();
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        var player = event.getPlayer();
        for (Identifier id: outcomingPackets) {
            SpigotUtils.addChannel(player, id.toString());
            srv.logDebug("Registered %s for %s".formatted(id, player.getName()));
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        var player = event.getPlayer();
        for (Identifier id: outcomingPackets) {
            SpigotUtils.removeChannel(player, id.toString());
            srv.logDebug("Unregistered %s for %s".formatted(id, player.getName()));
        }
        srv.userManager().onUserLeave(player.getUniqueId());
    }

    private BaseComponent[] getSyntaxError(CommandSyntaxException exception) {
        return ComponentSerializer.parse(ComponentUtils.text(exception.getMessage()).color("red").build().toString());
    }

    private class BukkitTickRunnable extends BukkitRunnable {

        @Override
        public void run() {
            srv.tick();
        }
    }
}
