# RoN Community Server

A matchmaking and ranked play system for [Reign of Nether](https://github.com/SoLegendary/reignofnether), an RTS mod for Minecraft Forge 1.20.1.

Provides automated queue, map rotation, scoring, and multi-instance game servers — without modifying the RoN mod itself.

## Architecture

```
[Players] → [Velocity Proxy + ron-proxy]
                ├→ [Paper Lobby + ron-lobby]
                ├→ [Forge Instance 01 + RoN + ron-instance]  ← RCON
                └→ [Forge Instance 02 + RoN + ron-instance]  ← RCON
```

Servers can be on **different machines**. The proxy communicates with instances via RCON (built into Minecraft). Plugin messages are used between the lobby and proxy (always has players connected).

| Component | Type | Description |
|-----------|------|-------------|
| `ron-common` | Java library | Shared code: SQLite database, scoring logic |
| `ron-proxy` | Velocity plugin | Matchmaker — polls instances via RCON, routes players |
| `ron-lobby` | Paper plugin | Queue system, commands, leaderboard |
| `ron-instance` | Forge mod | Map swapping, victory detection, score updates, RCON commands |

## Communication

```
Lobby ←→ Proxy     Plugin messages (player transfer requests, match finding)
Proxy ←→ Instances  RCON (status polling, map loading, map listing)
```

The proxy polls each instance via RCON to get their state and available maps. Polling is hybrid: every **5 seconds** while a match is active or the instance is reachable, and every **30 seconds** when idle/unreachable to reduce noise. When the lobby requests a match, the proxy picks an instance and sends a load command via RCON.

## Instance States

```
IDLE → PREPARING → (restart) → READY → RUNNING → FINISHED → (reset) → IDLE
```

- **IDLE** — booted, RCON available, waiting for a load command
- **PREPARING** — received load command, flag file written, server halting for restart
- **READY** — booted with a fresh map, waiting for players
- **RUNNING** — match in progress
- **FINISHED** — match ended, results available via `ron-status` until the proxy issues `ron-reset`
- *(OFFLINE)* — proxy-side label for instances that fail to respond to RCON

## Maps

Each instance has its own local `maps/` directory. Each subfolder is a RoN world save plus an `rtsmap.json` describing the map's modes. The folder name is opaque — only `rtsmap.json` is read.

```
maps/
├── Duality/
│   ├── rtsmap.json
│   ├── level.dat
│   └── region/
├── Berlingrad/
│   └── ...
└── 4Mountains/
    └── ...
```

### `rtsmap.json` format

```json
{
  "name": "Duality",
  "author": ["Soly"],
  "startPositions": [ ... ],
  "defaultMode": "1v1",
  "modes": {
    "1v1":   [[0], [1]],
    "2v2":   [[0, 2], [1, 3]],
    "ffa":   [[0], [1], [2], [3]]
  }
}
```

- `modes` is a map of mode name → array of teams, where each team is an array of start-position indices.
- `defaultMode` must be one of the keys in `modes`.
- A mode named `ffa` (case-insensitive) is treated as flexible: `minPlayers = 2`, `maxPlayers = total slots`. Any other mode requires exactly `total slots` players.
- `author` is optional and shown to players when the match starts.

### Creating a map

1. Set up a RoN server and build your map.
2. Place RoN start-position blocks (colored = auto-allied) where each team should spawn.
3. Save the world.
4. Write an `rtsmap.json` in the world folder describing the modes and start-position indices.
5. Copy the folder into the instance's `maps/` directory.
6. Instance scans on next boot or `ron-maps` poll.

Different instances can have different maps. Put smaller maps on smaller servers, big maps on beefier ones.

## Match Lifecycle

```
1.  Players /queue in lobby
2.  At 2+ players (configurable), a 120s fill window opens for more players to join
3.  Fill ends → room locks, a 60s map+mode vote starts (/vote <number>)
4.  Vote ends → lobby asks proxy to find a match for the chosen map/mode
5.  Proxy picks an available instance and sends ron-loadmap via RCON
6.  Instance writes flag file, halts, process manager restarts it
7.  Instance boots with new map, state becomes READY
8.  Proxy detects READY, transfers players from lobby
9.  Players pick start positions/factions, RoN match begins (RUNNING)
10. Victory detected (last player/team standing)
11. State becomes FINISHED; proxy reads matchResult, updates SQLite
12. Players see victory screen
13. Proxy sends ron-reset; instance halts, restarts, goes back to IDLE
```

Fill and vote durations are configurable via `ron-lobby`'s `config.yml`.

## Scoring

| Event | Points |
|-------|--------|
| Win | +25 base, up to +50 bonus vs higher-rated opponent, minimum +10 |
| Loss | -15 base, reduced to -5 vs much higher-rated opponent, floor at 0 |

### Ranks

| Rank | Points |
|------|--------|
| Bronze | 0–99 |
| Silver | 100–249 |
| Gold | 250–499 |
| Platinum | 500–999 |
| Diamond | 1000+ |

## Commands

### Player commands (lobby)

| Command | Description |
|---------|-------------|
| `/queue` | Join the matchmaking queue |
| `/leave` | Leave the queue |
| `/vote <number>` | Vote for a map+mode option during the lock phase |
| `/matches` | List running matches and available servers |
| `/spectate <instance>` | Watch a running match |
| `/leaderboard` | Top 10 players |
| `/rank` | Your stats |

### OP commands (lobby)

| Command | Description |
|---------|-------------|
| `/ronstatus` | Full server status: instances, running matches, player counts, capabilities (perm: `ron.status`) |

### RCON commands (instance)

| Command | Description |
|---------|-------------|
| `ron-maps` | Returns available maps + modes as JSON |
| `ron-status` | Returns state, current map/mode, players, game time, and `matchResult` when FINISHED, as JSON |
| `ron-loadmap <map>` | Validates map, writes flag file, halts server for map swap |
| `ron-setmode <mode>` | Set the game mode for the current map (must exist in `rtsmap.json`) |
| `ron-setprivate <true\|false>` | Mark the next match as private/unranked |
| `ron-playerscores <json>` | Push pre-match scores from the proxy (used for matchmaking and rating delta) |
| `ron-reset` | Reset instance back to IDLE after a match (called by proxy once results are recorded) |

## Building

### Prerequisites

- Java 17+
- RoN mod jar in `ron-instance/libs/`

### Build lobby + proxy

```bash
./gradlew build
```

### Build instance

```bash
mkdir -p ron-instance/libs/common
cp ron-common/build/libs/ron-common-1.0.0.jar ron-instance/libs/common/
cd ron-instance
./gradlew build
```

## Deployment

### Velocity proxy

Place `ron-proxy-1.0.0.jar` in `plugins/`. Install [Ambassador](https://github.com/adde0109/Ambassador).

Configure `plugins/ron-proxy/config.json`:
```json
{
  "instances": {
    "instance01": {
      "rconHost": "192.168.1.10",
      "rconPort": 25575,
      "rconPassword": "your-password"
    },
    "instance02": {
      "rconHost": "192.168.1.11",
      "rconPort": 25575,
      "rconPassword": "your-password"
    }
  }
}
```

Configure `velocity.toml`:
```toml
[servers]
lobby = "127.0.0.1:25566"
instance01 = "192.168.1.10:25565"
instance02 = "192.168.1.11:25565"
try = ["lobby"]
```

### Paper lobby

Place `ron-lobby-1.0.0.jar` in `plugins/`.

Configure `plugins/RonLobby/config.yml`:
```yaml
# Show the welcome chat message when a player joins the lobby
show-welcome-message: true

# Public queue match flow timings
queue:
  # Seconds the queue stays OPEN after minPlayers is reached, so more players can join.
  fill-seconds: 120
  # Seconds for the combined map+mode vote after the room is locked.
  vote-seconds: 60
```

The lobby has no database of its own — leaderboard/rank queries are answered by the proxy over plugin messaging.

### Forge game instances

Place `ron-instance-1.0.0.jar` + RoN mod + [Proxy-Compatible-Forge](https://modrinth.com/mod/proxy-compatible-forge) in `mods/`.

Enable RCON in `server.properties`:
```properties
enable-rcon=true
rcon.port=25575
rcon.password=your-password
```

Configure `serverconfig/ron-instance.toml`:
```toml
[maps]
# Path to the maps pool directory (relative to the server root or absolute).
pool = "maps"
```

Create the `maps/` directory and add map folders (each with an `rtsmap.json`). The instance has no database — match results are read by the proxy from `ron-status` (RCON) once the instance enters FINISHED, then written to the proxy's SQLite.

## Dependencies

- [Reign of Nether](https://github.com/SoLegendary/reignofnether) by SoLegendary (GPL-3.0)
- [Ambassador](https://github.com/adde0109/Ambassador) — Velocity plugin for Forge compatibility
- [Proxy-Compatible-Forge](https://modrinth.com/mod/proxy-compatible-forge) — Forge mod for Velocity support
- [Velocity](https://papermc.io/software/velocity) — Minecraft proxy
- [Paper](https://papermc.io/software/paper) — Minecraft server

## License

MIT — see [LICENSE](LICENSE)
