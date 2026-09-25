# FiguraFSB plugin — integration guide for other plugins

FiguraFSB is a Spigot/Paper plugin that has the Figura client's avatar upload/download and ping traffic
**relayed directly by the server the player is connected to**, instead of by the official Figura cloud
(MC 1.21.8, Java 21).

This document explains how other plugins can use FiguraFSB's communication features.

---

## 1. Setup

1. Put `figura-fsb-0.1.6-spigot.jar` into the server's `plugins/` folder. (Data folder: `plugins/FiguraFSB/`)
2. Declare the dependency in your plugin's `plugin.yml`:

```yaml
depend: [FiguraFSB]     # hard dependency
# or
softdepend: [FiguraFSB] # soft dependency
```

3. At build time, add `figura-fsb-0.1.6-spigot.jar` to the classpath as `compileOnly`.

```groovy
dependencies {
    compileOnly files("libs/figura-fsb-0.1.6-spigot.jar")
}
```

There are two entry points:

| Class | Purpose |
|--------|------|
| `org.figuramc.figura.server.api.FiguraFSBApi` | Static method API (sending pings, sending/receiving server packets) |
| `org.figuramc.figura.server.api.FSBPingEvent` | Bukkit event (detecting/blocking client pings) |

---

## 2. Detecting pings sent by clients — `FSBPingEvent`

When a player's avatar script calls `pings:...()`, the ping arrives at the server, and `FSBPingEvent`
fires **on the main thread** right before the ping is broadcast.

```java
@EventHandler
public void onFiguraPing(FSBPingEvent event) {
    UUID sender = event.getSenderUuid(); // player who sent the ping
    int pingId   = event.getPingId();    // the avatar's ping function id (see below)
    byte[] data  = event.getData();      // raw payload in Figura's serialization format
    boolean sync = event.isSync();       // whether the ping also runs on the sender's own client

    // To stop the ping from propagating under certain conditions:
    event.setCancelled(true); // not broadcast to other players
}
```

- The event fires **only for pings that pass** FSB's own rate limit checks (count/size).
- Cancelling it stops the ping from reaching other players (the sending client is not notified).
- `getPingId()` is the internal number Figura assigns to each ping function of an avatar.
  It is always the same for the same avatar, so you can use it as a key for "this ping of this avatar",
  but it may change when the avatar is edited. Interpreting the contents of `getData()` (deserializing
  Lua values) is not recommended because it is a Figura-internal format — use it for detecting,
  blocking and relaying pings.

## 3. Sending pings from the server — `FiguraFSBApi`

```java
// To one player only: the ping function of avatarOwner's avatar runs on the receiver's client
FiguraFSBApi.sendPing(avatarOwnerUuid, pingId, data, receiverPlayer);

// To every FSB-connected player (avatarOwner included)
FiguraFSBApi.broadcastPing(avatarOwnerUuid, pingId, data);
```

Use cases: controlling how pings are relayed (e.g. delivering them only to spectators), replaying
recorded pings, and so on. Because `pingId`/`data` must follow Figura's internal conventions, the
recommended approach is usually to reuse values captured in `FSBPingEvent` as-is (relaying).
**To send arbitrary data from a plugin to an avatar, the standard way is server packets (section 4 below).**

## 4. Two-way communication between plugins and avatar scripts — server packets (recommended)

Separately from pings, FSB has a name-based custom packet channel between the server (plugin) and
**the player's own avatar script**. It is well suited to server-driven content — for example, a
CommandHelper extension can expose these packets as script functions.

### Server → avatar

```java
// Plugin (byte[] in any format, up to 32,762 bytes)
FiguraFSBApi.sendServerPacket(player, "myplugin:hello", data);
```

```lua
-- That player's avatar script (runs on the host only)
server_packets["myplugin:hello"] = function(buffer)
    -- buffer: FiguraBuffer holding the byte[] sent by the server, unchanged
end
```

### Avatar → server

```lua
-- Avatar script
local buf = data:createBuffer()
buf:writeByteArray("hello")
buf:setPosition(0)
server_packets:sendPacket("myplugin:hello", buf)
```

```java
// Plugin
FiguraFSBApi.onServerPacket("myplugin:hello", (name, senderUuid, sender, avatarOwner, data) -> {
    // Called on the main thread. sender is null if the player is offline
    // avatarOwner = owner of the "avatar script" that sent this packet
    //   (with default settings, always the same as senderUuid)
});
```

Notes:

- Packet names live in **one namespace shared by all plugins**, and only one listener can be registered
  per name (registering again overwrites it). Always prefix them, as in `"yourplugin:packetname"`.

### Non-host avatar communication (allowNonHostPackets)

By default, server packets only talk to "the player's own equipped avatar (the host)".
Turning on the following in `config.json` lets **other players' avatar scripts loaded on a client**
send and receive `server_packets` as well:

```json
"allowNonHostPackets": true
```

- Example: B's avatar script, loaded on A's client, calls `server_packets:sendPacket`
  → it reaches the server listener with `senderUuid = A`, `avatarOwner = B`.
- Server → a specific avatar: `FiguraFSBApi.sendServerPacket(receiverPlayer, avatarOwnerUuid, name, data)`
  → the listener of avatarOwner's avatar runs on the receiver's client (allows a different effect per viewer).
- While this switch is off (the default), the server silently drops C2S packets where `avatarOwner != sender`.
- Trust model warning: a malicious client can forge avatarOwner, so enable this only in closed
  environments where the server assigns and vets the avatars.

## 5. Miscellaneous

```java
// Whether the player joined with an FSB-capable Figura client (i.e. completed the handshake)
boolean ok = FiguraFSBApi.isConnected(player.getUniqueId());
```

- All APIs are meant to be called from the main thread, and calls are silently ignored when FSB is not
  initialized or the target is not connected.
- Server console debug log: JVM argument `-DfiguraDebug=true`
- In-game admin command: `/fsb` (see `org.figuramc.figura.server.FiguraPermissionNodes` for permission nodes)
- **Config reload**: `/fsb reload` in game (permission node `figura.reload`, OP by default) or
  `FiguraFSBApi.reloadConfig()` from code. This re-reads `plugins/FiguraFSB/config.json`.
  However, values delivered through the handshake, such as ping/avatar limits, apply **only to players who
  join after the reload**, while `allowNonHostPackets` takes effect immediately.

## 6. Replay (Flashback) recording — state/signal design

FSB pings and server packets travel as custom payloads on the game connection, so they are **recorded in
Flashback replay files** and delivered to the client's avatars again on playback (handled by the Figura
client's replay compatibility layer). Unlike relaying state through scoreboard team display names, you only
need to send **on change, and only to the clients that need it**, which greatly reduces server load.

To reproduce avatar state reliably in replays, keep the following in mind:

1. **Pings you send yourself are not recorded.** A recording only keeps "packets the client received from
   the server (S2C)". To keep your own avatar's state in your own replay, the server has to **send that
   state back to you as an S2C packet** (e.g. `sendServerPacket(me, myUuid, ...)`).

2. **Sequential playback behaves exactly like live play.** The avatar script only needs to implement
   "set the state when a packet arrives, keep it otherwise" (event-driven, the same as live).
   Flashback replays recorded packets at their original ticks, so state variables naturally persist until
   the next packet arrives. **No special handling is needed.**

3. **Only timeline jumps (seeking) need extra handling — and that is the server's job.**
   Flashback splits a replay into **5-minute chunks**; on a jump it rewinds to the start (snapshot) of the
   chunk containing the target point and fast-forwards from there. However, **Flashback snapshots store only
   vanilla state, not the avatars' custom state**, so state packets sent before the target point (in an
   earlier chunk) are not delivered again and the state is lost. The fix:
   - **state (persistent state)**: the server **re-sends the entire current state as S2C packets at a low
     rate (every 1–2 seconds)**. Then, wherever you jump to, an up-to-date state packet lies just before that
     point and is delivered again during the fast-forward.
     (This is the part that **the server**, not the avatar, has to re-send periodically.)
     - **It must be the full state.** When restoring after a seek, the chunk snapshot holds no custom avatar
       state and only the packets in the re-processed stretch are applied, so if you send deltas (changed
       values only), only those fields are restored and the rest are left with wrong values (initial values,
       or the values from before the jump).
     - Optimization: combine **deltas (sent immediately on change)** for live responsiveness with
       **full snapshots (at a low rate)** for seek restoration. Send full snapshots only to recording
       clients, packed into a single packet (up to 32 KB) with `FiguraBuffer`.
   - **signal (one-shot events)**: signals inside the fast-forwarded stretch of a seek can run again, so
     have the avatar **expire them quickly with a countdown** (for example, a 5-tick countdown).

   **Division of roles**: avatar = event-driven state/signal logic (the same as live); server = send
   immediately on change, plus periodically re-send the current state at a low rate. If you already relay
   state through a scoreboard with a "state + countdown signal" design, you can keep that design as-is and
   only switch the transport from the scoreboard to FSB server packets: server load goes down and replay
   compatibility is preserved.

## 7. Internals (advanced)

You can also access the shaded `org.figuramc.figura.server.*` classes directly:

- `FiguraServer.getInstance()` — the server singleton. `userManager()`, `customPackets()`, `sendPacket(uuid, packet)`
- `Events.registerHandler(PingReceivedEvent.class, handler)` — subscribe directly to FSB's internal event bus instead of the Bukkit event
- Channel protocol: plugin message channels `figura:*` (pings `figura:c2s/ping` / `figura:s2c/ping`,
  server packets `figura:ping/server`, avatar transfer `figura:avatars/*`)

However, internal classes may change between FSB versions, so where possible, use only `FiguraFSBApi`
and `FSBPingEvent`.
