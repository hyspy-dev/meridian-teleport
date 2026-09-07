# meridian-teleport

A thin **Layer-2** module that teleports the player through meridian-core's
`Player` building blocks. It owns no protocol logic; a Hytale update can't break
it.

## How it works (mechanism lives in core)

Hytale movement is **client-authoritative**: the client renders and reports its
own position in `ClientMovement` (packet 108), and the server trusts it. The
server's only check on the reported `absolutePosition` is
`ValidateUtil.isSafePosition` — `NaN` / `Infinity` are rejected and **nothing
else** (no distance / speed / delta; a large jump only logs a warning and resets
velocity in `PlayerProcessMovementSystem`).

Because the client is authoritative for its own view, *rewriting the outgoing
packet alone does not move the player's camera* — the client immediately reports
its real position again. So a real teleport sends **two** packets:

1. **To the client** — a `ClientTeleport` (the same packet the server uses for
   `/tp` / knockback). The client relocates its own view.
2. **To the server** — a forged `ClientMovement` carrying the new
   `absolutePosition`. This is the key bit: a server-initiated teleport only
   makes the client send back a `teleportAck` (not a fresh absolute), so without
   it the server keeps the old position and snaps the client back. The
   unvalidated server applies any finite absolute, so it pins at the target.

We never forge the `teleportAck` upstream (that path *is* validated,
`PendingTeleport.validate`); with no pending server teleport the ack is ignored.

Exposed as `Player` building blocks:

```java
player.teleport(new Vec3(x, y, z));   // real teleport (ClientTeleport to client)
player.holdPosition(new Vec3(x, y, z)); // server-side offset (client stays put)
player.clearHold();
```

## Controls

| Control | Building block | Effect |
|---------|----------------|--------|
| **Teleport** | `Player.teleport` | Real teleport — the player actually moves. |
| **Server-side offset** | `Player.holdPosition` / `clearHold` | The server / other players see the player elsewhere while their own client stays put (advanced). |
| **Recent jumps** | `Player.teleport` | The last 10 targets, newest first — click one to go back there. |

Settings are remembered between runs: the X / Y / Z target, map teleport, safe
teleport and everything under *Chain teleport*. The server-side offset is not —
it is a live effect on a session, and a box that came back ticked with nothing
behind it would be lying.

## Recent jumps

Every teleport that actually goes somewhere — the button, the map, another
module through the `Teleport` service — puts its target at the head of the list,
which keeps the last 10. Going back by clicking a row does *not* add an entry:
the list stays the trail of places you went to, not of times you looked at it.

Its use is a desync — the client and the server disagreeing about where the
player is. The last target is the position that was meant, so jumping to it
again settles the argument.

## Usage

Drop the jar in the server's `modules/` dir alongside `meridian-core`. In the
settings panel: type X / Y / Z (or **Fill from current position**, or click a
target in ESP's nearest list), then **Teleport**.
