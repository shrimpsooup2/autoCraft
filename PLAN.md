# autoCraft — build plan

Client-side Fabric mod. You give it resources + amounts; it survives, paths, mines, crafts tools,
travels to the Nether when a resource lives there, stores loot and logs off when the goal is met.

## Stack
- Minecraft **26.3**, Fabric Loader 0.19.5, Fabric API, Java 25, Loom (`./gradlew build`, `./gradlew runClient`).
- Mojang names (26.x is unobfuscated — no mappings).
- No external AI libs: the neural net is a tiny MLP in plain Java.

## Commands (typed in chat, never sent to the server)
| Command | Effect |
|---|---|
| `!ac collect <resource> <amount> [<resource> <amount> ...]` | start a job (`diamond 10 iron 32 quartz 64`) |
| `!ac stop` / `!ac pause` / `!ac resume` | control the job |
| `!ac status` | progress, current behavior, prerequisite, health/food |
| `!ac base` | mark current position as the storage base (chests near it are used) |
| `!ac logoff on\|off` | disconnect when the job is done (default on) |
| `!ac goto <x> <y> <z>` | debug: pathfind to a block |
| `!ac resources` | list known resource names (any block item id also works, e.g. `minecraft:oak_log`) |

## Architecture
One client-tick loop. Every tick the `Bot` releases all inputs, then asks behaviors **in priority
order**; the first that returns `true` owns the tick (subsumption). Lower behaviors keep their state
and resume when higher ones let go. Planners that need materials (tools, portal obsidian) post a
**need** on the bot; the collector serves needs before the job.

```mermaid
flowchart TD
  T[client tick] --> R0[0 LavaReflex: swim out toward solid ground, fire resistance]
  R0 --> R1[1 FallReflex: lava landing: steer, water/block into the lava, fire resistance; high fall: water clutch]
  R1 --> R2[2 DefenseReflex: shield arrows, deflect/dodge ghast fireballs, back off lit creepers]
  R2 --> R3[3 EatReflex]
  R3 --> R4[4 EffectReflex: milk / honey]
  R4 --> C[5 Combat: ThreatNet decides fight or flee; neutral mobs left alone]
  C --> GO[6 Goto: debug walk]
  GO --> F[7 Finish: goal met -> store + log off]
  F --> G[8 Gear: armor, shield, tools, nether kit via crafting/smelting]
  G --> ST[9 Storage: junk drop, bundles, shulkers, base chests]
  ST --> P[10 Portal: find/build/light portal, change dimension]
  P --> CO[11 Collect: scan -> path -> mine -> pick up, else explore / boat]
```

| Package | Responsibility |
|---|---|
| `com.autocraft` | entrypoint, `Bot` (job, needs, behavior loop), chat logging |
| `command` | `!ac` chat command parser |
| `goal` | `Job`, `Requirement` (items, source blocks, strategy, Y level, dimension), `Resources` registry |
| `world` | `WorldScanner` (section-skipping block scan, entity queries), `BlockUtil` (walkable/breakable/danger, break time) |
| `control` | `Controls` (keys, rotation, mine/attack/place/use), `InvUtil` (find/select/swap/click/drop) |
| `path` | A* `Pathfinder` (walk, diagonal, jump-up, staircase down, safe fall, dig-down, pillar, bridge, mine-through, swim), goals, executor, `Navigator` |
| `behavior` | the priority behaviors above + `Harvest` (walk → mine → pick up) |
| `ai` | `ThreatNet` MLP + online training, persisted to `config/autocraft/threatnet.json` |
| `craft` | hardcoded recipes, planner (what to gather/craft/smelt next), 2x2 / table crafting, furnace smelting |
| `travel` | boats over water/ice, nether portals (find, build, light, enter, return) |

### Neural net (ThreatNet)
Inputs: own health, armor, best weapon damage, shield, food, hostile count, nearest distance, summed
mob danger, creeper nearby, ranged count, night/nether. Small tanh hidden layer, sigmoid output =
“fighting is a good idea”. Bootstrapped on synthetic samples from a hand-written teacher so it is
sane on first run, then updated online after every fight (won without losing >50% health → 1,
else → 0) and saved.

## Phases
| # | Phase | Status |
|---|---|---|
| 1 | **Skeleton** — Gradle/Loom project, entrypoint, `!ac` commands, `Bot` loop, logging | code complete |
| 2 | **Movement** — `Controls`, `BlockUtil`, incremental A* + executor, `!ac goto` | code complete |
| 3 | **Collection** — registry, scanner, mine + pick up, explore (ore Y level + tunnel; surface walk), log off | code complete |
| 4 | **Survival** — lava escape, fall/lava clutch, eating, milk, shield/back-off, ThreatNet combat | code complete |
| 5 | **Tools & crafting** — pickaxe tier detection, planner, 2x2 + table crafting, smelting, armor/shield | code complete |
| 6 | **Storage** — junk dropping, bundles, shulker boxes, base chests with deposited counts | code complete |
| 7 | **Nether** — kit (gold boots, flint and steel, iron pickaxe, blocks); obsidian mined or cast from lava; build + light or reuse portal; remember portals; go back for gear materials or base storage; nether mob rules | code complete |
| 8 | **Travel** — boats over water and ice for long exploration legs | code complete |
| 9 | **Hardening** — in-game testing, stuck recovery, tuning thresholds from logs | next |

"Code complete" = compiles against the real 26.3 client jar; not yet run in-game (see Build).

## Build & test
- `./gradlew build` → `build/libs/autocraft-<version>.jar` (drop into `.minecraft/mods` with Fabric API).
- `./gradlew runClient` starts a dev client; try `!ac goto`, then `!ac collect log 16`, `!ac collect iron 8`.
- Loom must download Minecraft's libraries from `libraries.minecraft.net`; build on a network that allows it.

## Future goals
- **Elytra travel**: if an elytra is carried, use it for long trips; firework stock management (craft from
  paper + gunpowder), elytra durability checks with a safe-landing reserve.
- **Mending upkeep**: keep mending gear repaired by holding/wearing damaged mending items while collecting XP.
- **Biome-aware search**: read biomes to pick exploration targets for biome-specific or biome-rich resources
  (sand → desert/beach, clay → swamp/river, gold → badlands, emerald → mountains, mangrove → swamp).

## Known limits
- Servers with anti-xray or strict anti-cheat will fight the bot (scanning sees hidden ores; rotations snap).
- End travel, enchanting, villager trading are out of scope.
- Job targets are gatherable items; crafted items are produced only as tool/kit prerequisites.
- Lava escape needs solid ground within ~4 blocks (or the last safe spot) or a fire resistance potion; a fall
  into a wide lava lake with no water bucket, no blocks and no solid edge in reach can still be fatal.
