# Hoardkeeper

A Fabric client mod for Minecraft **26.3** that knows what is in your chests.

- **Scan** the containers around you — chests, trapped chests, barrels, shulker boxes, and the
  shulker boxes inside them — without a single screen popping up.
- **Find** any item: `/hoard search diamond`, or hold it and press **V**. The chests that have it
  light up, each with its count.
- **See** counts where you already look: a line in every item's tooltip, and a card above the
  crosshair when you crouch at a container.
- **Put away** your inventory in singleplayer: every stack walks into the container that already
  holds the same item.

Everything stays on your computer. Hoardkeeper has no network code of its own.

## Where it may open containers

Opening containers automatically is something many servers forbid. So Hoardkeeper **acts** only
where that is clearly fine and **observes** everywhere:

- In **singleplayer** (and a world you host on LAN) everything works.
- On a **server**, the scanner stays off until you run `/hoard allow` there. `/hoard disallow`
  turns it off again. Only allow it where the server's rules permit it.
- On every server, search, tooltips and the peek card still work. They use the containers you
  **open by hand**, which Hoardkeeper remembers as you go.

The deposit mode is singleplayer-only, always: servers usually have a command of their own for it.

## Commands

| Command | Purpose |
|---|---|
| `/hoard start [radius]` | Scan the containers around you (default `defaultRadius`, capped at `maxRadius`) |
| `/hoard start chunks <n>` | Scan whole chunks instead of a radius |
| `/hoard stop` | End the scan now — it also finishes by itself once everything in reach is done |
| `/hoard status` | Progress: count, throughput, chunk coverage |
| `/hoard search <item>` | Light up every container holding the item (tab completion lists what you have) |
| `/hoard search` | Clear the highlight |
| `/hoard deposit [on\|off]` | Singleplayer: put your inventory away — see below |
| `/hoard allow` / `disallow` | Let the scanner open containers on this server, or stop it |
| `/hoard resume` | Continue the last scan a disconnect cut short |
| `/hoard retry-failed` | Re-queue containers that failed for a reason worth another try |
| `/hoard export` | Write the last scan as `report.json` and show the path |
| `/hoard peek [on\|off]` · `/hoard reminder [on\|off]` | The crouch card · the rescan reminder |
| `/hoard clear` | Forget the scan in memory without writing anything |

`/hoard` alone shows the short help. After a disconnect mid-scan you get a clickable `resume`
offer on the next join — Hoardkeeper never resumes on its own.

## How the scan works

The server only sends a container's slots once it is opened. So the scanner sends a real
interaction per container, catches the server's answer before a screen is built, reads the slots
and closes the container again. To the server it is an ordinary open-read-close; to you, nothing
appears. The server allows one open container per player, so the scan runs one container at a
time — about ten a second on a good connection, paced and backed off so it never floods anyone.

The radius is a **horizontal cylinder**, not a sphere: height is not limited, so a tall storage
room is scanned whole.

## Search

`/hoard search <item>` lights up every container holding it in the storage you are standing in,
with the amount above each one; the action bar counts down what is left. Open a lit container and
only that one goes out. `/hoard search` clears it, and so does the timeout
(`searchHighlightSeconds`). Hold an item and press **V** (rebindable under *Controls → Hoardkeeper*)
for the same search without typing.

"The storage you are standing in" is the connected area you have measured around you. Standing
outside every measured area, the search covers the whole dimension and says how far the nearest hit
is.

## Tooltip, peek card and the rescan reminder

- **Tooltip:** inside a measured storage, every item's tooltip gains `Storage: 3,104 in 6 chests`
  (or `Storage: none`).
- **Peek card:** crouch while looking at a container and a card shows what it held when last seen,
  sorted by amount, with how long ago that was.
- **Rescan reminder:** walk into a storage last measured more than `rescanSuggestAfterDays` ago
  (default 7) and chat offers a clickable `[Rescan]`. It never starts one by itself.

## Putting things away (singleplayer)

- **`/hoard deposit`** turns on a mode: the containers in the 3×3 chunks around you that already
  hold something you carry light up, labelled with what goes there. Walk past them and every
  matching stack goes in — no screen opens. `/hoard deposit` again turns it off and says how much
  it put away.
- **Sneak and right-click a container with an empty hand** to fill just that one.

A stack only goes where **exactly** the same item already is — enchantments, name and potion
included. The hotbar, armour, off-hand, anything with durability and bundles never move. A shulker
box moves only when all 27 slots are full stacks of one item, and only to that item (loose, or in
another such box); loose items may go next to a box that holds only them.

## What you open by hand counts too

A scan is a snapshot; whoever empties a chest afterwards makes it wrong. So Hoardkeeper remembers
what is in a container you open yourself and updates its map when you close it — silently, with no
extra packet to the server.

Where the scanner may act, only containers a scan already knows are updated (a looted bastion stays
out of your storage). Where it may not, every container you open is recorded — that is how search
and tooltips work on such a server at all.

## Where things are

| | |
|---|---|
| Config | `config/hoardkeeper.json`, created with defaults on first start |
| Map, scans, observations | `<game directory>/hoardkeeper/<server>/…` |

A finished scan is deleted once the map holds it (not before an hour has passed, so `export` still
finds your last scan); `keepSessions: true` keeps them all.

**Coming from storage-scanner?** On first start Hoardkeeper carries `config/storage-scanner.json`
and the `storage-scanner/` folder over. Remove the storage-scanner jar — the two refuse to run
together.

## Add-ons

Other mods can hook into Hoardkeeper through the `hoardkeeper:addon` entrypoint
(`dev.hoardkeeper.api.Addon`): they get told when a scan ends, may add subcommands under `/hoard`
and may keep a finished scan on disk until they are done with it.

## Installing

Client-side, **Minecraft 26.3**, Fabric Loader **0.19.5** or newer, **Fabric API**. Drop the jar
into `mods`.

## Building

Java **25**:

```sh
./gradlew build          # the jar lands in build/libs/hoardkeeper-<version>.jar
./gradlew runClient      # a dev client with the mod loaded
```

## Tests

`./gradlew test` runs the unit tests. The integration tests are Fabric **client gametests**: a
real client and a dedicated server in one process, one scenario after another. CI runs them under a
virtual display on every push; locally:

```sh
./gradlew runClientGameTest -Pscenario=all      # or one: -Pscenario=search
```

| Scenario | Proves |
|---|---|
| `boot` | mod loaded, server up, client joined, `/hoard` registered |
| `scan` | every container scanned, the double chest as one, the shulker's contents read, **no screen** while scanning, the inventory stays in sync |
| `search`, `search-key` | the right containers light up — by command and by key |
| `retry`, `resume` | failed containers come back; a relog mid-scan finishes the same scan |
| `passive`, `measured` | containers opened by hand reach the map; a corner rescan costs nothing |
| `peek`, `nudge` | the crouch card and the rescan reminder |
| `allow` | an unlisted server refuses to scan and still records what you open |
| `deposit` | the right stacks go into the right containers, nothing else moves |

## License

MIT
