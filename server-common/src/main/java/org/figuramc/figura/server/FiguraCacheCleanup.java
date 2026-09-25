package org.figuramc.figura.server;

import org.figuramc.figura.server.avatars.FiguraServerAvatarManager.AvatarMetadata;
import org.figuramc.figura.server.utils.Hash;
import org.figuramc.figura.server.utils.Utils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Reclaims disk space under the plugin data folder.
 *
 * <p>Two things accumulate and are never cleaned on their own:
 * <ul>
 *   <li>{@code avatars/} - an avatar stays on disk as long as any user owns or equips it.
 *       Its {@code .mtd.json} carries that ownership, but the metadata can drift out of sync
 *       (a user file removed by hand, an upload interrupted halfway). Stale metadata keeps
 *       the payload alive forever.</li>
 *   <li>{@code users/} - a record is written for everyone who ever connected and is never
 *       removed, so players who visited once keep an entry indefinitely.</li>
 * </ul>
 *
 * <p>Ownership is therefore <b>recomputed from the user files</b> rather than trusted from the
 * avatar metadata: the user records are what the plugin actually reads when a player joins, so
 * they are the authority. Anything no user references is unreachable, whatever its metadata says.
 *
 * <p>Skipped on purpose:
 * <ul>
 *   <li>avatars flagged {@link AvatarMetadata#cleanupProtection()}</li>
 *   <li>avatars currently held in memory (a transfer may be in flight)</li>
 *   <li>records of players who are online</li>
 * </ul>
 */
public final class FiguraCacheCleanup {
    private FiguraCacheCleanup() {}

    /** How aggressively user records are treated. Avatar handling is the same in both. */
    public enum Mode {
        /** Delete only records that hold nothing: no owned avatars, no equipped avatar, no badges. */
        EMPTY_ONLY,
        /**
         * Delete the record of every offline player, then collect the avatars that lose their
         * last owner. Destructive: those players lose uploaded avatars and start clean.
         */
        PURGE_OFFLINE
    }

    public record Report(
            int avatarsDeleted, long avatarBytesFreed,
            int danglingDeleted, long danglingBytesFreed,
            int usersDeleted, long userBytesFreed,
            int avatarsKeptProtected, int avatarsKeptInUse, int usersKeptOnline,
            List<String> errors
    ) {
        public long totalBytesFreed() { return avatarBytesFreed + danglingBytesFreed + userBytesFreed; }
        public int totalDeleted() { return avatarsDeleted + danglingDeleted + usersDeleted; }
    }

    /**
     * @param dryRun when true nothing is written or removed; the report describes what would go
     */
    public static Report run(FiguraServer server, Mode mode, boolean dryRun) {
        List<String> errors = new ArrayList<>();

        // Users still in memory may hold changes that were never written. Flush first, otherwise
        // their avatars would look unreferenced and get collected out from under them.
        if (!dryRun) {
            try { server.userManager().saveAll(); }
            catch (Exception e) { errors.add("saveAll failed: " + e); }
        }

        Path usersDir = server.getUsersFolder();
        Path avatarsDir = server.getAvatarsFolder();

        Set<UUID> online = new HashSet<>();
        for (UUID uuid: server.userManager().loadedUsers()) {
            FiguraUser u = server.userManager().getUserOrNull(uuid);
            if (u != null && u.online()) online.add(uuid);
        }

        // ---- users -------------------------------------------------------------------------
        int usersDeleted = 0, usersKeptOnline = 0;
        long userBytes = 0;
        Set<UUID> droppedUsers = new HashSet<>();

        for (File f: listFiles(usersDir)) {
            UUID uuid = uuidOfUserFile(f.getName());
            if (uuid == null) continue;
            if (online.contains(uuid)) { usersKeptOnline++; continue; }

            boolean drop;
            if (mode == Mode.PURGE_OFFLINE) {
                drop = true;
            } else {
                Boolean empty = userRecordIsEmpty(f);
                if (empty == null) { errors.add("unreadable user file: " + f.getName()); continue; }
                drop = empty;
            }
            if (!drop) continue;

            userBytes += f.length();
            usersDeleted++;
            droppedUsers.add(uuid);
            if (!dryRun && !f.delete()) errors.add("could not delete " + f.getName());
        }

        // ---- referenced avatar hashes ------------------------------------------------------
        // Rebuilt from the user files that survive. In a dry run the doomed files are still on
        // disk, so they are excluded explicitly.
        Set<String> referenced = new HashSet<>();
        for (File f: listFiles(usersDir)) {
            UUID uuid = uuidOfUserFile(f.getName());
            if (uuid == null || droppedUsers.contains(uuid)) continue;
            collectHashes(f, referenced, errors);
        }
        // In-memory users are authoritative over their file, which may lag by a tick.
        for (UUID uuid: server.userManager().loadedUsers()) {
            if (droppedUsers.contains(uuid)) continue;
            FiguraUser u = server.userManager().getUserOrNull(uuid);
            if (u == null) continue;
            if (u.equippedAvatar() != null) addHash(referenced, u.equippedAvatar().right().hash());
            u.ownedAvatars().values().forEach(p -> addHash(referenced, p.hash()));
        }

        Set<String> inMemoryAvatars = new HashSet<>();
        for (Hash h: server.avatarManager().loadedAvatars()) inMemoryAvatars.add(hex(h));

        // ---- avatars -----------------------------------------------------------------------
        int avatarsDeleted = 0, keptProtected = 0, keptInUse = 0, dangling = 0;
        long avatarBytes = 0, danglingBytes = 0;

        Set<String> payloads = new HashSet<>();
        for (File f: listFiles(avatarsDir)) {
            String n = f.getName().toLowerCase(Locale.ROOT);
            if (n.endsWith(".nbt")) payloads.add(n.substring(0, n.length() - 4));
        }

        for (String hex: payloads) {
            if (referenced.contains(hex)) continue;
            if (inMemoryAvatars.contains(hex)) { keptInUse++; continue; }
            if (isProtected(avatarsDir, hex, errors)) { keptProtected++; continue; }

            avatarsDeleted++;
            for (File f: avatarFiles(avatarsDir, hex)) {
                if (!f.exists()) continue;
                avatarBytes += f.length();
                if (!dryRun && !f.delete()) errors.add("could not delete " + f.getName());
            }
        }

        // Metadata whose payload is gone is unusable on its own.
        for (File f: listFiles(avatarsDir)) {
            String n = f.getName().toLowerCase(Locale.ROOT);
            String hex;
            if (n.endsWith(".mtd.json")) hex = n.substring(0, n.length() - 9);
            else if (n.endsWith(".mtd")) hex = n.substring(0, n.length() - 4);
            else continue;
            if (payloads.contains(hex)) continue;
            dangling++;
            danglingBytes += f.length();
            if (!dryRun && !f.delete()) errors.add("could not delete " + f.getName());
        }

        return new Report(avatarsDeleted, avatarBytes, dangling, danglingBytes,
                usersDeleted, userBytes, keptProtected, keptInUse, usersKeptOnline, errors);
    }

    // -------------------------------------------------------------------------------------

    private static File[] listFiles(Path dir) {
        File[] files = dir.toFile().listFiles(File::isFile);
        return files == null ? new File[0] : files;
    }

    private static File[] avatarFiles(Path dir, String hex) {
        return new File[] {
                dir.resolve(hex + ".nbt").toFile(),
                dir.resolve(hex + ".mtd.json").toFile(),
                dir.resolve(hex + ".mtd").toFile()
        };
    }

    /** Accepts 32 hex chars plus .pl.json or the legacy .pl; anything else is not ours. */
    private static UUID uuidOfUserFile(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        String hex;
        if (n.endsWith(".pl.json")) hex = n.substring(0, n.length() - 8);
        else if (n.endsWith(".pl")) hex = n.substring(0, n.length() - 3);
        else return null;
        if (hex.length() != 32 || !hex.matches("[0-9a-f]+")) return null;
        try {
            return UUID.fromString(hex.replaceFirst(
                    "(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})", "$1-$2-$3-$4-$5"));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static void addHash(Set<String> out, Hash hash) {
        if (hash == null) return;
        byte[] raw = hash.get();
        if (raw != null && raw.length > 0) out.add(Utils.hexFromBytes(raw).toLowerCase(Locale.ROOT));
    }

    /** @return null when the file could not be parsed */
    private static Boolean userRecordIsEmpty(File f) {
        FiguraUser user = readUser(f);
        if (user == null) return null;
        return user.equippedAvatar() == null
                && user.ownedAvatars().isEmpty()
                && user.prideBadges().isEmpty();
    }

    private static void collectHashes(File f, Set<String> out, List<String> errors) {
        FiguraUser user = readUser(f);
        if (user == null) { errors.add("unreadable user file: " + f.getName()); return; }
        if (user.equippedAvatar() != null) addHash(out, user.equippedAvatar().right().hash());
        user.ownedAvatars().values().forEach(p -> addHash(out, p.hash()));
    }

    private static FiguraUser readUser(File f) {
        UUID uuid = uuidOfUserFile(f.getName());
        if (uuid == null) return null;
        try {
            return f.getName().toLowerCase(Locale.ROOT).endsWith(".json")
                    ? FiguraUser.load(uuid, f.toPath())
                    : FiguraUser.loadByteBuf(uuid, f.toPath());
        } catch (Exception e) {
            return null;
        }
    }

    /** Unreadable metadata counts as protected: never delete something we failed to understand. */
    private static boolean isProtected(Path dir, String hex, List<String> errors) {
        File json = dir.resolve(hex + ".mtd.json").toFile();
        if (!json.exists()) return false;   // no metadata at all: nothing claims it
        try {
            String s = new String(Files.readAllBytes(json.toPath()), StandardCharsets.UTF_8);
            AvatarMetadata md = AvatarMetadata.read(s);
            return md != null && md.cleanupProtection();
        } catch (IOException | RuntimeException e) {
            errors.add("unreadable metadata, kept: " + json.getName());
            return true;
        }
    }

    private static String hex(Hash hash) {
        return Utils.hexFromBytes(hash.get()).toLowerCase(Locale.ROOT);
    }
}
