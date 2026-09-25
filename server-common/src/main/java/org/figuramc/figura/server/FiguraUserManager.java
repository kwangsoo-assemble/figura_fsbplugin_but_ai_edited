package org.figuramc.figura.server;

import org.figuramc.figura.server.events.Events;
import org.figuramc.figura.server.events.users.LoadPlayerDataEvent;
import org.figuramc.figura.server.events.users.SavePlayerDataEvent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.BitSet;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public final class FiguraUserManager {
    private final FiguraServer parent;
    private final HashMap<UUID, FiguraUser> users = new HashMap<>();
    private int pingsTickCounter = 0;
    private int autosaveTickCounter = 0;

    public FiguraUserManager(FiguraServer parent) {
        this.parent = parent;
    }

    public FiguraUser getUserOrNull(UUID playerUUID) {
        return users.get(playerUUID);
    }

    public boolean userExists(UUID player) {
        return users.containsKey(player) ||
                parent.getUserdataFile(player).toFile().exists() ||
                parent.getOldUserdataFile(player).toFile().exists();
    }

    public FiguraUser getUser(UUID player) {
        return users.computeIfAbsent(player, (p) -> loadPlayerData(player));
    }

    public FiguraUser setupOnlinePlayer(UUID uuid) {
        FiguraUser user = getUser(uuid);
        user.setOnline();
        user.update();
        return user;
    }


    private FiguraUser loadPlayerData(UUID player) {
        LoadPlayerDataEvent playerDataEvent = Events.call(new LoadPlayerDataEvent(player));
        if (playerDataEvent.returned()) return playerDataEvent.returnValue();
        Path dataFile = parent.getUserdataFile(player);
        if (dataFile.toFile().exists()) {
            try {
                return FiguraUser.load(player, dataFile);
            }
            catch (Exception e) {
                // Warning: previously this failure fell through to the legacy fallback and silently became an
                //   **empty user**, and the next save overwrote the corrupt file with a well-formed one, making
                //   recovery impossible. Periodic saving brings that "next save" forward automatically, so the
                //   file must be quarantined.
                parent.logError("Corrupt userdata for " + player + "; quarantining to .corrupt", e);
                try {
                    Files.move(dataFile, dataFile.resolveSibling(dataFile.getFileName() + ".corrupt"),
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException ignored) {}
                return new FiguraUser(player, new BitSet(), null, new HashMap<>());
            }
        }
        return FiguraUser.loadByteBuf(player, parent.getOldUserdataFile(player));
    }

    /**
     * Saves a single user. Honors {@link SavePlayerDataEvent} and isolates errors <b>per user</b>.
     *
     * <p>Warning: isolation is required because {@code FiguraUser.save} rethrows IOException as a
     * RuntimeException. Without it, the first failing user <b>aborts the save for everyone else</b>, and
     * in that case {@code close()} never even reaches {@code users.clear()}.
     * Periodic saving puts this weakness on a permanent schedule, so it has to be closed off first.
     *
     * @param onlyIfChanged if true, writes only users whose content changed (for periodic saving)
     */
    private void saveUser(FiguraUser user, boolean onlyIfChanged) {
        try {
            if (Events.call(new SavePlayerDataEvent(user)).isCancelled()) return;
            if (onlyIfChanged) user.saveIfChanged(parent.getUserdataFile(user.uuid()));
            else user.save(parent.getUserdataFile(user.uuid()));
        } catch (Exception e) {
            parent.logError("Failed to save userdata for " + user.uuid(), e);
        }
    }

    /** For periodic saving only — writes only users whose content changed. */
    public void saveChanged() {
        for (var user: users.values()) saveUser(user, true);
    }

    public void forEachUser(Consumer<FiguraUser> func) {
        users.forEach((id, user) -> {
            if (user.online()) {
                func.accept(user);
            }
        });
    }

    public void onUserLeave(UUID player) {
        users.computeIfPresent(player, (uuid, pl) -> {
            saveUser(pl, false);
            pl.setOffline();
            return pl;
        });
    }

    /**
     * Writes every loaded user to disk without dropping them from memory.
     * <p>
     * Cache cleanup reads ownership straight off the user files, so anything still only held
     * in memory has to be flushed first or it would look unreferenced and get collected.
     */
    public void saveAll() {
        // Warning: do not add a dirty/online filter here. Cleanup recomputes ownership from the user files,
        //   so **every user must be flushed** before the scan. Periodic saving uses saveChanged() instead.
        for (var user: users.values()) saveUser(user, false);
    }

    /** UUIDs of users currently held in memory (online or not yet evicted). */
    public java.util.Set<UUID> loadedUsers() {
        return new java.util.HashSet<>(users.keySet());
    }

    public void close() {
        for (var user: users.values()) saveUser(user, false);
        users.clear();
    }

    public void tick() {
        if (pingsTickCounter == 20) {
            forEachUser(user -> user.pingCounter().reset());
            pingsTickCounter = 0;
        }
        pingsTickCounter++;

        // Note: periodic save. The equipped state used to reach disk **only on leave/clean shutdown** —
        //   killing the server lost that session's uploads entirely, and after a restart other clients
        //   were advertised the old hash, so the outdated avatar was rendered.
        // Warning: synchronous, on the main thread. This plugin ticks via runTaskTimer (not async), which is
        //   the same thread as mutators, commands and packet handling, so no synchronization is needed.
        int interval = parent.config().autosaveIntervalTicks();
        if (interval > 0 && ++autosaveTickCounter >= interval) {
            autosaveTickCounter = 0;
            saveChanged();
        }
    }

    private record FutureHandle(UUID user, CompletableFuture<FiguraUser> future) {}
}
