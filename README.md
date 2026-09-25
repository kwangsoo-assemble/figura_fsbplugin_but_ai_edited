# figura_fsbplugin_but_ai_edited

> A **Figura Server Backend (FSB)** server plugin for Paper / Purpur **1.21.8**.
> This is an **AI-assisted fork**: the FSB server implementation from the official [Figura](https://github.com/FiguraMC/Figura)
> repository's 1.20.6 branch (the `server-common` and `spigot` modules) is split out into a standalone plugin, ported
> to 1.21.8 and extended. The server you are connected to relays Figura avatar uploads, downloads and pings instead of
> the official cloud.

> ⚠ **This is an unofficial fork** and is not affiliated with FiguraMC. Most changes were written with an AI coding
> assistant (Claude Code) and checked by the maintainer in real use.
> ⚠ The companion client is [figura_core_but_ai_edited](https://github.com/kwangsoo-assemble/figura_core_but_ai_edited) —
> because the protocol changed, it is **not wire-compatible with the official Figura client.**

## At a glance

| Item | Value |
|---|---|
| Server | Paper / Purpur 1.21.8 (`api-version: 1.21`) |
| Java | 21 |
| Plugin | name `FiguraFSB`, version `0.1.6-but-ai-edited.1` (see 11), command `/fsb`, data folder `plugins/FiguraFSB/` |
| Companion client | figura_core_but_ai_edited (shares `server-common/` as the same source) |

## Changes from upstream

Compared against the `server-common` and `spigot` modules of upstream branch `1.20.6` at
[`eff5f43`](https://github.com/FiguraMC/Figura/commit/eff5f43cd3c542bc58641ae2d43d3e8f73d22043).
In the **Code** paths under each item, `server-common/…/` is short for `server-common/src/main/java/org/figuramc/figura/server/`
and `spigot/…/` for `spigot/src/main/java/org/figuramc/figura/server/`. **(new)** marks files that upstream does not have.

### 1. Standalone plugin, ported to 1.21.8

Upstream, these were modules inside the Figura client repository. `server-common` (protocol and logic with no
Minecraft dependency) and `spigot` (the Bukkit plugin) are split out into a separately built project and adapted to
Paper / Purpur 1.21.8. **This repository holds the original of `server-common/`** — the client fork copies it (if the
two differ, communication breaks).

Code: the root build files (`settings.gradle`, `build.gradle`, `gradle.properties`) are written from scratch ·
`server-common/build.gradle` · `spigot/build.gradle` · `spigot/src/main/resources/plugin.yml` (name `FiguraFSB`, `api-version`)

### 2. Server packets for other players' avatars (protocol change)

`CustomFSBPacket` becomes `(avatarOwner UUID, id, data)` — avatars other than the host's can exchange packets with the
server. The packet id is the Java `String.hashCode()` of the packet name. ⚠ This breaks wire compatibility with the
official FSB.

Code: `server-common/…/packets/CustomFSBPacket.java` · `server-common/…/FiguraCustomPackets.java`

### 3. Setting `allowNonHostPackets` (default `false`)

When `false`, the server drops client-to-server packets whose `avatarOwner` differs from the sender. Server-to-client
packets are always delivered, regardless of this value.

Code: `server-common/…/FiguraServerConfig.java` · `server-common/…/packets/handlers/c2s/C2SCustomFSBPacketHandler.java`

### 4. `/fsb reload`

Permission `figura.reload` (operators by default). From code: `FiguraFSBApi.reloadConfig()`. Values sent on join
(ping and avatar limits) apply to players who join after the reload.

Code: `server-common/…/commands/FiguraServerCommands.java` · `server-common/…/FiguraServer.java` · `server-common/…/FiguraPermissionNodes.java`

### 5. Ping rate limit bug fixed

Upstream only sent an error packet for pings over the limit and **did not block them**. They are blocked now.

Code: `server-common/…/packets/handlers/c2s/C2SPingPacketHandler.java`

### 6. Public API for other plugins (`org.figuramc.figura.server.api`)

- `FiguraFSBApi` — `sendPing` / `broadcastPing`, `sendServerPacket(receiver[, avatarOwner], name, data)`,
  `onServerPacket(name, listener)` (one listener per name — registering again replaces it), `isConnected`, `reloadConfig`
- `FSBPingEvent` — a Bukkit event fired when a ping is received (cancellable)
- `PingReceivedEvent` — an internal `server-common` event
- Usage, examples and a replay-friendly design for sending state are in `API.md`.

Code: `spigot/…/api/FiguraFSBApi.java` · `spigot/…/api/FSBPingEvent.java` · `server-common/…/events/pings/PingReceivedEvent.java` (all new) ·
`spigot/…/FiguraSpigot.java`

### 7. Newer Paper compatibility

On versions where `CraftPlayer.addChannel` and `removeChannel` no longer exist, the plugin falls back to manipulating
the `channels()` set directly.

Code: `spigot/…/SpigotUtils.java`

### 8. Players who are already online survive a plugin reload

Upstream, after the plugin was enabled again (server `/reload`, a plugin manager), it no longer knew the players who
were already online — server-to-client packets for them were silently dropped until they reconnected. Now the plugin
registers online players again when it is enabled and sends them a fresh handshake. Clients that accept it carry on
without reconnecting, and older clients ignore it.

Code: `spigot/…/FiguraSpigot.java`

### 9. Periodic saving of user data

Upstream saved user data (equipped avatars and badges) only on quit and on a clean shutdown, so if the server was
killed, that session's uploads were lost.

- Setting `autosaveIntervalSeconds` (default 300 seconds) — `0` turns it off and behaves like upstream.
- **Atomic saves** — written to a temporary file, fsynced, then swapped in. The result is either the old content or
  the new content, never a truncated file.
- Only users whose content changed are written, and users with nothing to save do not get a file.
- Save errors are **isolated per user** — if one user fails, the others are still saved.
- A user file that cannot be read is moved aside as `*.corrupt`, instead of being read as an empty user and
  overwritten by the next save as before.

Code: `server-common/…/FiguraUser.java` · `server-common/…/FiguraUserManager.java` · `server-common/…/FiguraServerConfig.java`

### 10. `/fsb cleanup` — removing unused avatars and player records

Upstream never deleted avatar files or player records, so they piled up. Permission `figura.cleanup` (operators by default).

- `/fsb cleanup` — **only reports** what would be deleted (changes nothing).
- `/fsb cleanup confirm` — deletes avatars nobody uses, metadata whose avatar file is gone, and empty player records.
- `/fsb cleanup purge` → `purge confirm` — also deletes the record of every player who is not online.
  ⚠ The avatars those players uploaded go with them, and this cannot be undone.
- Who uses an avatar is **recomputed from the user files**, not taken from the avatar metadata.
  Protected avatars, avatars currently loaded in memory, and records of online players are skipped.

Code: `server-common/…/commands/FiguraCleanupCommand.java` · `server-common/…/FiguraCacheCleanup.java` (both new) ·
`server-common/…/avatars/FiguraServerAvatarManager.java`

### 11. Version number

To avoid confusion with the upstream FSB, the version carries a [SemVer pre-release](https://semver.org/#spec-item-9) tag —
`0.1.6-but-ai-edited.1` (jar: `figura-fsb-0.1.6-but-ai-edited.1.jar`). It matches the tag of the companion client (figura_core_but_ai_edited).

Code: root `gradle.properties`

## Settings (`plugins/FiguraFSB/config.json`)

| Key | Default | Meaning |
|---|---|---|
| `pingsRateLimit` | `32` | Pings per second per player |
| `pingsSizeLimit` | `1024` | Ping bytes per second per player |
| `avatarSizeLimit` | `102400` | Avatar size limit (bytes) |
| `avatarCountLimit` | `1` | Avatars per player |
| `allowNonHostPackets` | `false` | Allow packets from avatars other than the host's (**added**) |
| `autosaveIntervalSeconds` | `300` | Periodic save interval, `0` turns it off (**added**) |

## Building

JDK 21 is required.

```
./gradlew build
```

Output: `spigot/build/libs/figura-fsb-<version>.jar` (a jar with `server-common` bundled inside). The
`*-unshaded.jar` is an intermediate file — do not put it on a server.

## License

**PolyForm Noncommercial 1.0.0**, the same as the upstream Figura repository (`LICENSE.md`) — noncommercial use only.
