# Hunger Games — Spigot 1.21 Plugin

A Hunger Games-style arena plugin for **Spigot 1.21.x**. Procedurally generates circular obsidian arenas, runs matches with difficulty-tiered game modes, dynamic terrain phases, scattered loot chests, and YAML-defined custom power items.

## Requirements

- Java 21
- Maven 3.8+
- Spigot 1.21 server

## Build

```bash
mvn package
```

The plugin JAR is output to `target/HungerGames-1.0.0.jar`. Copy it into your server's `plugins/` folder and restart.

## Quick Start

1. Start your Spigot 1.21 server with the plugin installed.
2. Run these commands in order (each arena builds at **0, 20, 0** in its own void world):

```
/hg arena create myarena 100
/hg arena setmode myarena standard
/hg arena generate myarena
```

4. Players join and you start the match:

```
/hg join myarena
/hg start myarena
```

## Commands

| Command | Permission | Description |
|---------|------------|-------------|
| `/hg join [arena]` | `hungergames.play` | Join a lobby |
| `/hg leave` | `hungergames.play` | Leave the current game |
| `/hg arena create <name> <radius> [height]` | `hungergames.admin` | Register a new arena |
| `/hg arena setmode <name> <mode>` | admin | Assign a game mode |
| `/hg arena generate <name>` | admin | Build the obsidian arena |
| `/hg arena delete <name>` | admin | Delete arena and world |
| `/hg arena list` | admin | List all arenas |
| `/hg start [arena]` | admin | Force-start a match |
| `/hg stop [arena]` | admin | Force-stop a match |
| `/hg loot give <player> <itemId>` | admin | Give a custom power item |
| `/hg loot spawnbox [tier]` | admin | Place a loot chest |
| `/hg loot list` | admin | List custom item IDs |
| `/hg reload` | admin | Reload all configs |

## Game Modes

Built-in modes in `plugins/HungerGames/gamemodes/`:

| ID | Difficulty | Description |
|----|------------|-------------|
| `peaceful_forest` | EASY | Calm forest arena |
| `standard` | NORMAL | Nether transform + storm |
| `nether_chaos` | HARD | Fast nether + lava rise |
| `aquatic_doom` | HARD | Ocean flood from edges |
| `scorched_earth` | EXTREME | Rapid terrain cycling |
| `volcano` | HARD | Lava caldera grows into an erupting volcano |

All hard/extreme modes set `maxMatchSeconds` so matches end on a timer. The boss bar shows remaining match time during grace and active phases.

### Match Pacing

Gamemode YAML supports:

- `maxMatchSeconds` — total match duration (0 = no limit)
- `durationMatchRatio` on terrain phases — phase length as a fraction of `maxMatchSeconds` (e.g. `0.25` = 25% of match)
- `durationSeconds: 0` — continuous phase until the match ends (used for final hazards like lava rise or volcano eruption)

### Tree Placement

Optional `treePlacement:` block per gamemode controls arena generation groves:

```yaml
treePlacement:
  enabled: true
  cellSize: 5
  clusterThreshold: 0.04
  maxDensityChance: 0.90
  minHeight: 4
  maxHeight: 8
  excludeCenterRadius: 8
  species:
    - oak
    - spruce
    - birch
```

Uses jittered-grid cluster noise (same algorithm as volcano arenas). Standard and Scorched Earth now get sparse trees on mixed/barren terrain.

### Volcano Mode

Set an arena to volcano mode before generating:

```
/hg arena setmode <name> volcano
/hg arena generate <name>
```

The arena center starts as a **lava caldera** (no center cornucopia). With `arctic.iceWorldStart: true` (the volcano default) the rest of the disc generates as ice, glaciers, and snow; `forestRadius` still places the spawn ring and settlements. Loot chests are scattered at match start on solid ground above the lava zone.

During the match, `VOLCANO_ERUPTION` builds the cone, converts ice to nether, launches TNT, and runs two mob factions. A decaying sine on match progress makes ice recapture ground (tug-of-war) until the last ~8% of the timer, when the volcano wins and no ice remains. Between games the arena is restored from the saved world copy.

Hostile mobs (vanilla, spawners, plugin armies, faction units) stay off during grace and the opening `FOREST` phase. They start when the eruption/nether phase begins.

Use `initialTerrain: volcano` when creating custom modes that share this arena layout.

#### Volcano biome war (`VOLCANO_ERUPTION` keys)

| Key | Default | Meaning |
|-----|---------|---------|
| `biomeWarEnabled` | `1` | Ice/volcano tug-of-war |
| `biomeWarProgressPower` | `1.15` | Curve of volcanic trend vs time (`1` = linear) |
| `biomeWarOscillationCycles` | `3.0` | Ice comeback pulses over the match |
| `biomeWarOscillationAmplitude` | `0.22` | How far ice can push the front back |
| `biomeWarOscillationDampening` | `1.4` | Pulses fade as the match ends |
| `biomeWarIceGoneMatchRatio` | `0.92` | After this match fraction, remaining ice is forced out |
| `biomeWarIceRecaptureEnabled` | `1` | Convert nether back to ice when the front retreats |
| `biomeWarFreezeColumnsPerTick` | `400` | Batched ice freeze (20 Hz, existing forest templates) |
| `biomeWarRecaptureColumnsPerTick` | `56` | Batched ice recapture |

#### Ice vs volcano factions

| Key | Default | Meaning |
|-----|---------|---------|
| `factionWarEnabled` | `1` | Two armies that fight each other |
| `iceFactionMaxMobsStart` / `End` | `12` / `0` | Ice cap vs volcanic dominance |
| `volcanoFactionMaxMobsStart` / `End` | `3` / `14` | Volcano cap vs volcanic dominance |
| `iceFactionMobs` | polar bear, stray, wolf | Ice roster |
| `volcanoFactionMobs` | blaze, magma cube, wither skeleton, piglin brute, hoglin | Volcano roster |
| `factionAggroRange` | `28` | Rival search radius |

Players are not assigned a faction. Piglin horde hunt-locks still override faction targeting.

#### Volcano TNT

Rate is exponential in volcano/biome progress (phase tick is 1 Hz, so these are TNT/sec), spread is area-uniform in a widening disc, and live TNT is capped.

| Key | Default | Meaning |
|-----|---------|---------|
| `projectileRatePerSecondMin` / `Max` | `2` / `16` | Endpoints of the exponential curve |
| `projectileRateExponent` | `2.4` | Higher = quieter early, heavier late |
| `projectileMaxPerTick` | `10` | Hard cap per second |
| `maxActiveVolcanoTnt` | `28` | Live primed-TNT cap |
| `projectileSpreadRadiusStart` | `40` | Early-game landing disc |
| `projectileSpreadRadiusEnd` | `0` | `0` = arena edge; grows over the match |
| `tntFuseMaxTicksEnd` | `170` | Late-game fuse so shots can reach the rim |
| `tntMaxSpeedEnd` | `8.4` | Late-game ballistic cap |

### Creating a Custom Game Mode

Add a new YAML file to `plugins/HungerGames/gamemodes/`:

```yaml
id: my_mode
displayName: "My Mode"
difficulty: NORMAL
description: "Custom arena experience"
minPlayers: 2
maxPlayers: 16
gracePeriodSeconds: 30
lootTier: normal
lootBoxDensity: medium
initialTerrain: forest
borderShrink:
  enabled: true
  durationSeconds: 90
  shrinkPerTick: 0.5
terrainPhases:
  - type: FOREST
    durationSeconds: 180
  - type: NETHER_TRANSFORM
    durationSeconds: 120
    transformSpeed: fast
```

Phase types: `FOREST`, `NETHER_TRANSFORM`, `OCEAN_RISE`, `LAVA_RISE`, `BORDER_SHRINK`, `STORM`, `VOLCANO_ERUPTION`

**Phase timing:** use `durationSeconds` for fixed lengths, `durationMatchRatio` for match-relative lengths, or `durationSeconds: 0` for a continuous final phase.

**LAVA_RISE / OCEAN_RISE:** `blocksPerTick`, `riseBlocksPerTick`, `intensityRampMatchRatio`, mob spawn params (`maxHostileMobs`, `mobTypesEarly`, `mobTypesLate`, etc.)

**STORM:** `lightningMinIntervalMs`, `lightningMaxIntervalMs`, `lightningMinIntensity`, `stormFireBurnChancePerTick`, `stormFireBurnChecksPerTick`

**Border shrink is off by default.** To enable it for a mode, add to the gamemode YAML:

```yaml
borderShrink:
  enabled: true
  durationSeconds: 90
  shrinkPerTick: 0.5
```

Or use the shorthand `borderShrink: true` to apply defaults from `config.yml` (`game.border-shrink`). Any `BORDER_SHRINK` terrain phase is ignored unless border shrink is enabled.

`VOLCANO_ERUPTION` optional params: `maxConeHeight`, `buildBlocksPerTick`, `maxHostileMobs`, `mobSpawnRadiusMin`, `mobSpawnRadiusMax`, `projectileIntervalMin`, `projectileIntervalMax`

Run `/hg reload` after adding or editing modes.

## Custom Power Items

Defined in `plugins/HungerGames/custom_items.yml`. Built-in items:

| ID | Type | Ability | Effect |
|----|------|---------|--------|
| `jump_boots` | Armor | JUMP_BOOST | Permanent jump boost while worn |
| `titan_heart` | Held | EXTRA_HEALTH | +4 bonus hearts while held |
| `ghast_wand` | Active | GHAST_BLAST | Right-click fireball (no block damage) |
| `void_orb` | Active | BLACK_HOLE | Right-click pulls nearby entities |
| `speed_charm` | Active | SPEED_BURST | Brief speed burst |
| `regen_talisman` | Held | REGEN_AURA | Regeneration while held |
| `storm_caller` | Active | LIGHTNING_STRIKE | Lightning at crosshair target |
| `blink_pearl` | Active | BLINK | Teleport forward 8 blocks |
| `seismic_gauntlet` | Active | SHOCKWAVE | Knockback ring around you |
| `phantom_cloak` | Active | INVISIBILITY_BURST | 6 seconds of invisibility |
| `berserker_totem` | Held | STRENGTH_AURA | Strength while held |
| `lava_walkers` | Armor | FIRE_RESISTANCE | Fire resistance boots |
| `frost_prism` | Active | ICE_SNARE | Slow enemies in target area |
| `vampire_blade` | On attack | LIFE_STEAL | Heal 25% of damage dealt |
| `spike_mail` | Armor | THORNS_AURA | Reflect 30% damage to attackers |
| `loot_magnet` | Active | ITEM_MAGNET | Pull nearby dropped items |
| `red_shell` | Active | RED_SHELL | Homing shell that stuns enemy players |
| `egg_machine_gun` | Active | EGG_MACHINE_GUN | Toggle rapid egg fire (10/sec) |

**Item types:** `ACTIVE` (right-click), `PASSIVE_HELD`, `PASSIVE_ARMOR`, `ON_ATTACK` (weapon proc)

**Ability types:** `JUMP_BOOST`, `EXTRA_HEALTH`, `GHAST_BLAST`, `BLACK_HOLE`, `SPEED_BURST`, `REGEN_AURA`, `LIGHTNING_STRIKE`, `BLINK`, `SHOCKWAVE`, `INVISIBILITY_BURST`, `STRENGTH_AURA`, `FIRE_RESISTANCE`, `ICE_SNARE`, `LIFE_STEAL`, `THORNS_AURA`, `ITEM_MAGNET`, `RED_SHELL`, `EGG_MACHINE_GUN`

Active items support **cooldowns** (`params.cooldownSeconds`) and **limited uses** (`params.maxUses`). Uses are stored on the item and shown in lore; the item is removed when depleted. Cooldowns are per-item (two different active items have independent cooldowns).

### Adding a Custom Item

```yaml
items:
  my_item:
    displayName: "&eMy Power Item"
    material: BLAZE_ROD
    lore:
      - "&7Right-click to activate."
    type: ACTIVE
    ability: GHAST_BLAST
    params:
      cooldownSeconds: 10
      maxUses: 5
      yield: 1.0
```

Reference the item in `plugins/HungerGames/loot/tables.yml` under `pools.custom.<tier>`:

```yaml
pools:
  custom:
    normal:
      - customId: my_item
```

Ability types: see table above. Use `type: ON_ATTACK` for weapons that proc on hit.

### Testing in simulate mode

`/hg simulate [arena]` runs a solo match preview: you spectate during countdown, then at grace receive every custom item plus a **Test Target** zombie for combat abilities (lifesteal, thorns, shockwave, frost prism). Use `/hg stop` to end and restore your inventory.

## Configuration

Main config: `plugins/HungerGames/config.yml`

- `generation.blocks-per-tick` — arena build speed (default 4000)
- `generation.wall-thickness` — obsidian wall thickness (default 4)
- `loot.boxes.block-type` — scattered loot chest block (default CHEST)
- `loot.strip-custom-on-match-end` — remove custom items after match

Loot tables: `plugins/HungerGames/loot/tables.yml` and `loot_boxes.yml`

## Match Flow

```
LOBBY → COUNTDOWN → GRACE → ACTIVE → ENDING → RESET
```

- **Grace**: PvP off, cornucopia chests at center filled with loot
- **Active**: PvP on, terrain phases begin, loot chests scattered in arena
- **Phase drops**: Bonus loot chests spawn on terrain transitions

## License

Provided as-is for server use and modification.
