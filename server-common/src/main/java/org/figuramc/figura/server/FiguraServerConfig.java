package org.figuramc.figura.server;

import com.google.gson.annotations.SerializedName;

import java.util.UUID;

public final class FiguraServerConfig {
    @SerializedName("pingsRateLimit")
    private int pingsRateLimit = 32;
    @SerializedName("pingsSizeLimit")
    private int pingsSizeLimit = 1024;

    @SerializedName("avatarSizeLimit")
    private int avatarSizeLimit = 102400;
    @SerializedName("avatarCountLimit")
    private int avatarsCountLimit = 1;

    @SerializedName("allowNonHostPackets")
    private boolean allowNonHostPackets = false;

    @SerializedName("autosaveIntervalSeconds")
    private int autosaveIntervalSeconds = 300;

    /**
     * Periodic save interval in ticks. A value of 0 or less means <b>disabled</b>, i.e. the old behavior
     * (save only on leave, shutdown and cleanup).
     *
     * <p>Note: reverting is a single line, {@code "autosaveIntervalSeconds": 0} — if periodic saving causes
     * problems, this returns to exactly the previous code path without a rebuild.
     * An existing config.json without this key stays backward compatible, since Gson keeps the initial value.
     */
    public int autosaveIntervalTicks() {
        return autosaveIntervalSeconds <= 0 ? 0 : autosaveIntervalSeconds * 20;
    }

    /**
     * When true, avatar scripts of OTHER players loaded on a client may also exchange
     * server packets (the packet's avatarOwner may differ from the sending connection).
     * When false (default), only the host's own avatar packets are accepted.
     */
    public boolean allowNonHostPackets() {
        return allowNonHostPackets;
    }

    public int pingsRateLimit(FiguraServer server, UUID player) {
        return Integer.parseInt(server.getOption(player, FiguraPermissionNodes.FIGURA_PINGS_RATELIMIT).orElse(pingsRateLimit + ""));
    }

    public int pingsSizeLimit(FiguraServer server, UUID player) {
        return Integer.parseInt(server.getOption(player, FiguraPermissionNodes.FIGURA_PINGS_SIZELIMIT).orElse(pingsSizeLimit + ""));
    }

    public int avatarSizeLimit(FiguraServer server, UUID player) {
        return Integer.parseInt(server.getOption(player, FiguraPermissionNodes.FIGURA_AVATARS_SIZELIMIT).orElse(avatarSizeLimit + ""));
    }

    public int avatarsCountLimit(FiguraServer server, UUID player) {
        return Integer.parseInt(server.getOption(player, FiguraPermissionNodes.FIGURA_AVATARS_COUNTLIMIT).orElse(avatarsCountLimit + ""));
    }
}
