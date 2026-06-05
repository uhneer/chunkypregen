# Chunky Pregenerator

A Fabric mod for Minecraft 1.21.11 that automatically pre-generates chunks around players using [Chunky](https://modrinth.com/plugin/chunky) as the generation backend. Runs completely in the background, survives restarts mid-spiral, and works on both singleplayer and dedicated servers.

Vibecoded with Claude. MIT licensed.

---

## Why this over Distant Horizons' built-in pregenerator

DH's pregenerator fires once at a fixed radius from the center of the world. It doesn't track where the player actually goes, can't adjust as they move, has no concept of prioritizing nearby terrain over distant terrain, and can't pause when the server is struggling. Once it fires, that's it.

This mod generates in expanding spiral rings centered on the player's actual position. The terrain closest to the player is always done first. When the player moves far enough from the last generation center, a new bundle fires automatically, re-centered on them. If server TPS tanks during generation, Chunky gets told to pause and waits for recovery. When it recovers, it picks back up. If you quit mid-spiral, it saves exactly which ring it was on and resumes from there on rejoin. Chunky naturally skips already-generated chunks within a ring, so you lose at most a partial ring worth of work.

It also forces a Voxy LOD rebuild at the start of each bundle by briefly expanding and collapsing render distance. Without this, Voxy doesn't register newly pre-generated terrain as LOD data until something else triggers a rebuild.

---

## Features

**Spiral batch generation** — the bundle gets split into concentric rings using a quadratic ease-in curve. Ring spacing is tighter near the player and grows further out, which maps to how exploration actually works. Chunky skips already-generated chunks within each ring automatically, so overlapping rings are essentially free.

**Movement-triggered re-center** — once the player moves more than the deadzone distance from the last generation center, a new bundle fires centered on their new position. Defaults to half the generation radius, so there's always a buffer of pre-generated terrain ahead of wherever you're going.

**Mid-bundle resume on rejoin** — ring index, ring list, and center are written to the world's data folder on every save. On rejoin, after the join delay, it re-fires the current ring. Chunky skips the already-done portion automatically.

**TPS auto-pause** — Chunky pauses when server TPS drops below threshold (default 15 TPS) and resumes when it recovers (default 18 TPS). Prevents generation from compounding a performance problem.

**Voxy LOD integration** — when enabled, generation radius and deadzone track Voxy's actual render distance setting rather than static config values. Reads Voxy's config live via reflection, falls back to JSON parsing if reflection fails. Also fires a Voxy LOD rebuild (render distance expand/collapse cycle) at the start of each bundle so freshly generated terrain shows up in LOD without waiting.

**Chunky API poll** — completion detection goes through Chunky's actual `ChunkyAPI.isRunning()` via reflection rather than relying on log parsing or timers. Ring advance happens the moment Chunky actually finishes. Two consecutive false readings are required before advancing, as a debounce for the brief window right after a new task starts.

**Stall watchdog** — if Chunky goes completely silent (no `isRunning()=true` readings) for 10 straight minutes, the watchdog resets state so nothing hangs forever. Measures actual stall time rather than total runtime, so a legitimate multi-hour generation never trips it.

**Per-session progress relay** — Chunky's per-second log spam is suppressed. A clean progress summary goes out on a configurable interval instead.

**Nether auto-scale** — Nether generation radius is divided by 8 automatically to match the coordinate scale. A 250-chunk Overworld radius becomes ~31 chunks in the Nether.

**End gate** — the End won't pre-generate until a player has actually visited it. The End portal spawns the dragon on first entry, and you don't want that happening off-screen during pre-gen.

**Sodium settings UI** — all settings are exposed in a dedicated page in Sodium's video settings, with live tooltips that show what Voxy's render distance is actually reading as.

**Server and singleplayer** — runs on dedicated servers and singleplayer integrated servers. Client-side mods (Sodium, Voxy) are optional.

---

## Requirements

| Dependency | Required | Notes |
|---|---|---|
| [Fabric Loader](https://fabricmc.net/) | Yes | 0.19.3+ |
| [Fabric API](https://modrinth.com/mod/fabric-api) | Yes | Any 1.21.11 build |
| [Chunky](https://modrinth.com/plugin/chunky) | Yes | 1.4.55+ |
| [Sodium](https://modrinth.com/mod/sodium) | Yes | In-game settings UI + LOD rebuild cycle |
| [Voxy](https://modrinth.com/mod/voxy) | Optional | Lets Voxy's render distance drive generation radius |

[Chunky-Offline](https://modrinth.com/plugin/chunky-offline) is recommended alongside Chunky in singleplayer. It keeps Chunky running between world loads.

Without Voxy, the mod uses the static `generationRadius` and `triggerDistance` from config.

---

## Installation

Drop the jar into your `mods/` folder. Config generates on first launch at `config/chunkypregen.json`. If you have Sodium installed, all settings are in **Options > Video Settings > Chunky Pregenerator**. Reload config in-game with `/chunkypregen reload`.

---

## Default settings

| Setting | Default | Notes |
|---|---|---|
| Generation radius | 250 chunks | 4000 block radius |
| Deadzone | 2000 blocks | Half of 250 chunks x 16 |
| Voxy integration | Off | Enable to let Voxy's render distance drive the radius |
| Skip creative players | Off | Creative mode players count toward movement triggers |
| Nether auto-scale | On | Nether radius = global / 8 |
| TPS auto-pause | On | Pause at 15 TPS, resume at 18 |
| Spiral generation | On | Quadratic ease-in ring curve |
| End pre-gen | Off | Only fires after a player visits The End |
| Join delay | 15 seconds | Lets Chunky-Offline and Sodium finish initializing first |
| Progress relay | Every 5 minutes | Replaces Chunky's per-second log spam |
| Debug mode | Off | |

---

## Commands

All require op level 2.

| Command | |
|---|---|
| `/chunkypregen status` | State per dimension, current ring, last center, live progress |
| `/chunkypregen trigger` | Manually fire a generation bundle for all enabled dimensions |
| `/chunkypregen trigger <dim>` | Fire for a specific dimension |
| `/chunkypregen cancel` | Cancel all active Chunky jobs and reset state |
| `/chunkypregen reset` | Clear saved last-center positions |
| `/chunkypregen stats` | Session stats: jobs fired, estimated chunks, active time |
| `/chunkypregen config` | Dump current config to chat |
| `/chunkypregen debug` | Toggle debug logging |
| `/chunkypregen reload` | Reload config from disk without restart |
| `/chunkypregen setworld reset` | Clear the new-world init flag so first-join pre-gen fires again |

---

## How the spiral curve works

Ring radii follow `radius x ((i+1)/N)^2`. Ring count N scales automatically with radius and `generationRingStep`. At 250 chunks with default step of 25, you get 13 rings. At 1000 chunks you get 20. Each ring fires sequentially and the next ring only starts after `isRunning()` confirms the current one is done.

The quadratic curve means ring 1 covers the innermost ~1% of the area and the outer rings get progressively larger. The area near the player is always the first thing finished.

---

## License

MIT
