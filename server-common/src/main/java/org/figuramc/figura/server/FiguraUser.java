package org.figuramc.figura.server;

import org.figuramc.figura.server.avatars.EHashPair;
import org.figuramc.figura.server.json.FiguraUserStruct;
import org.figuramc.figura.server.packets.CustomFSBPacket;
import org.figuramc.figura.server.packets.Packet;
import org.figuramc.figura.server.utils.*;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;

import static java.nio.charset.StandardCharsets.UTF_8;

public final class FiguraUser {
    private final UUID player;
    private boolean online;
    private final PingCounter pingCounter = new PingCounter();
    private final BitSet prideBadges;
    private @Nullable Pair<String, EHashPair> equippedAvatar;

    private final HashMap<String, EHashPair> ownedAvatars;

    /**
     * The JSON last written to disk in this session. {@code null} if nothing has been written yet.
     *
     * <p>Note: a dirty boolean is not used because {@link #prideBadges()} returns the mutable {@code BitSet}
     * as-is and the badge command mutates that object directly, so mutator-based dirty marking
     * <b>inherently</b> misses badge changes. Observing the state has no missed-notification failure mode.
     */
    private transient String lastWritten;

    public FiguraUser(UUID player, BitSet prideBadges, Pair<String, EHashPair> equippedAvatar, HashMap<String, EHashPair> ownedAvatars) {
        this.player = player;
        this.online = false;
        this.prideBadges = prideBadges;
        this.equippedAvatar = equippedAvatar;
        this.ownedAvatars = ownedAvatars;
    }

    public UUID uuid() {
        return player;
    }

    public boolean online() {
        return online;
    }

    public boolean offline() {
        return !online;
    }

    public PingCounter pingCounter() {
        return pingCounter;
    }

    public BitSet prideBadges() {
        return prideBadges;
    }

    public @Nullable Pair<String, EHashPair> equippedAvatar() {
        return equippedAvatar;
    }

    public HashMap<String, EHashPair> ownedAvatars() {
        return ownedAvatars;
    }

    public void sendPacket(Packet packet) {
        FiguraServer.getInstance().sendPacket(player, packet);
    }

    private String serialize() {
        FiguraUserStruct struct = new FiguraUserStruct();
        if (equippedAvatar != null) {
            struct.equippedAvatar = equippedAvatar.left();
            struct.avatarHash = equippedAvatar.right();
        }
        struct.prideBadges = prideBadges;
        struct.ownedAvatars = ownedAvatars;
        return FiguraServer.getInstance().GSON.toJson(struct);
    }

    /**
     * Writes to a temporary file, fsyncs it, then renames it — "old content or new content, never truncated".
     *
     * <p>Warning: for periodic saving this is a <b>prerequisite</b>, not an option. Previously the target
     * file was truncated and written in place; if the server dies in that window, a truncated JSON is left
     * behind. A truncated file takes the legacy fallback in {@code loadPlayerData} and becomes an
     * <b>empty user</b>: the equipped avatar and badges silently disappear. Raising the save frequency
     * without fixing this would make a feature meant to protect data raise the risk of losing it.
     */
    private static void writeAtomic(Path file, String json) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (FileOutputStream fos = new FileOutputStream(tmp.toFile())) {
            fos.write(json.getBytes(UTF_8));
            fos.getFD().sync();
        }
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Writes only if the content differs from what was last written. Used by periodic saving only.
     *
     * @return true if the file was actually written to disk
     */
    public boolean saveIfChanged(Path file) throws IOException {
        String json = serialize();
        if (json.equals(lastWritten)) return false;
        // WARNING: never **create a new file** for a user who has nothing to save.
        //   FiguraUserManager.userExists() only checks that the file exists, so if an empty file is created,
        //   C2SFetchUserdataPacketHandler sends **empty userdata** instead of S2CUserdataNotFoundPacket, cutting
        //   off the client's official-cloud fallback (UserdataApplier.userdataNotFound -> getUserFromBackend).
        //   => Avatars of players who use only the official cloud become invisible on this server.
        //   Note: getUser() loads a user as soon as anyone merely looks them up, so without this guard
        //     periodic saving would scatter empty files for 'every player who was ever looked up'.
        //   Warning: if the file already exists, do write — a state where existing data was removed must be
        //   recorded too.
        if (isEmpty() && !Files.exists(file)) return false;
        writeAtomic(file, json);
        lastWritten = json;                 // Note: only after the write succeeded; otherwise a user whose
                                            //   write failed would never be written again
        return true;
    }

    /** Whether there is nothing to save at all (no equipped avatar, no owned avatars, no badges). */
    private boolean isEmpty() {
        return equippedAvatar == null && ownedAvatars.isEmpty() && prideBadges.isEmpty();
    }

    /** Always writes. Keeps the contract of the existing callers (leave, shutdown, cleanup) unchanged. */
    public void save(Path file) {
        try {
            String json = serialize();
            writeAtomic(file, json);
            lastWritten = json;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static FiguraUser load(UUID player, Path file) {
        file.getParent().toFile().mkdirs();
        File playerFile = file.toFile();
        try {
            FileInputStream fis = new FileInputStream(playerFile);
            String str = new String(fis.readAllBytes(), UTF_8);
            fis.close();
            FiguraUserStruct struct = FiguraServer.getInstance().GSON.fromJson(str, FiguraUserStruct.class);
            Pair<String, EHashPair> avatar = struct.equippedAvatar != null ? new Pair<>(struct.equippedAvatar, struct.avatarHash) : null;
            // Missing JSON keys deserialize to null — normalize them here or everything downstream hits an NPE
            FiguraUser user = new FiguraUser(player,
                    struct.prideBadges != null ? struct.prideBadges : new BitSet(),
                    avatar,
                    struct.ownedAvatars != null ? struct.ownedAvatars : new HashMap<>());
            // Note: what was just read is exactly what is on disk. Without recording it here, the first
            //   periodic save would rewrite every user once.
            user.lastWritten = user.serialize();
            return user;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Deprecated(forRemoval = true)
    public static FiguraUser loadByteBuf(UUID player, Path playerFile) {
        try (FileInputStream fis = new FileInputStream(playerFile.toFile())) {
            InputStreamByteBuf buf = new InputStreamByteBuf(fis);
            return loadByteBuf(player, buf);
        } catch (FileNotFoundException e) {
            return new FiguraUser(player, new BitSet(), null, new HashMap<>());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Deprecated(forRemoval = true)
    public static FiguraUser loadByteBuf(UUID player, IFriendlyByteBuf buf) {
        int length = buf.readVarInt();
        byte[] arr = buf.readBytes(length);
        BitSet prideBadges = BitSet.valueOf(arr);
        int equippedAvatarsCount = buf.readVarInt();
        Pair<String, EHashPair> equippedAvatar = null;
        for (int i = 0; i < equippedAvatarsCount; i++) {
            String id = new String(buf.readByteArray(256), UTF_8);
            Hash hash = buf.readHash();
            Hash ehash = buf.readHash();
            if (equippedAvatar == null) equippedAvatar = new Pair<>(id, new EHashPair(hash, ehash));
        }
        HashMap<String, EHashPair> ownedAvatars = new HashMap<>();
        int ownedAvatarsCount = buf.readVarInt();
        for (int i = 0; i < ownedAvatarsCount; i++) {
            String id = new String(buf.readByteArray(256), UTF_8);
            Hash hash = buf.readHash();
            Hash ehash = buf.readHash();
            ownedAvatars.put(id, new EHashPair(hash, ehash));
        }
        // Warning: data from the legacy format has not been written as JSON yet — lastWritten is left unset
        //   so that the first periodic save writes it once in the new format
        return new FiguraUser(player, prideBadges, equippedAvatar, ownedAvatars);
    }

    public Hash findEHash(Hash hash) {
        var avatar = equippedAvatar();
        if (avatar != null) {
            var pair = avatar.right();
            if (pair.hash().equals(hash)) return pair.ehash();
        }
        for (EHashPair pair: ownedAvatars.values()) {
            if (pair.hash().equals(hash)) return pair.ehash();
        }
        return null;
    }

    public void update() {

    }

    public void setOnline() {
        online = true;
    }

    public void setOffline() {
        online = false;
    }

    public void removeOwnedAvatar(String avatarId) {
        if (ownedAvatars.containsKey(avatarId)) {
            EHashPair avatar = ownedAvatars.remove(avatarId);
            try {
                FiguraServer.getInstance().avatarManager().getAvatarMetadata(avatar.hash()).owners().remove(uuid());
            } catch (RuntimeException re) {
                FiguraServer.getInstance().logError("Failed to remove owned avatar", re);
            }
        }
    }

    public void removeEquippedAvatar() {
        if (equippedAvatar != null) {
            try {
                FiguraServer.getInstance().avatarManager().getAvatarMetadata(equippedAvatar.right().hash()).equipped().remove(uuid());
                equippedAvatar = null;
            } catch (RuntimeException re) {
                FiguraServer.getInstance().logError("Failed to remove equipped avatar", re);
            }
        }
    }

    public void replaceOrAddOwnedAvatar(String avatarId, Hash hash, Hash ehash) {
        try {
            FiguraServer.getInstance().avatarManager().getAvatarMetadata(hash).owners().put(uuid(), ehash);
            ownedAvatars.put(avatarId, new EHashPair(hash, ehash));
        } catch (RuntimeException re) {
            FiguraServer.getInstance().logError("Failed to replace/add avatar", re);
        }
    }

    public void setEquippedAvatar(String avatarId, Hash hash, Hash ehash) {
        try {
            FiguraServer.getInstance().avatarManager().getAvatarMetadata(hash).equipped().put(uuid(), ehash);
            equippedAvatar = new Pair<>(avatarId, new EHashPair(hash, ehash));
        } catch (RuntimeException re) {
            FiguraServer.getInstance().logError("Failed to set equipped avatar", re);
        }
    }

    public int getAvatarsCountWithId(String avatarId) {
        return ownedAvatars().size() + (ownedAvatars().containsKey(avatarId) ? 0 : 1);
    }

    public void sendFSBPacket(String id, byte[] data) {
        sendFSBPacket(uuid(), id, data);
    }

    public void sendFSBPacket(UUID avatarOwner, String id, byte[] data) {
        sendPacket(new CustomFSBPacket(avatarOwner, id.hashCode(), data));
    }

    public static class PingCounter {
        private int bytesSent; // Amount of total bytes sent in last 20 ticks
        private int pingsSent; // Amount of pings sent in last 20 ticks

        public int bytesSent() {
            return bytesSent;
        }

        public int pingsSent() {
            return pingsSent;
        }

        public void addPing(int size) {
            pingsSent++;
            bytesSent += size;
        }

        public void reset() {
            bytesSent = 0;
            pingsSent = 0;
        }
    }
}
