# autoCraft — build plan

Client-side Fabric mod. You give it items + amounts — mined resources, mob drops, anything craftable/smeltable,
or things that need a procedure (honey, wool, oxidized copper, trial keys, the heavy core); it survives, paths,
mines, hunts, crafts tools and the requested items, travels to the Nether when a resource lives there, builds
farms or your own schematics (alone or as a team of bots), stores loot and logs off when the goal is met.

**How it works, animated:** `docs/how-it-works/how-it-works.mp4` (2.6 min), or open `docs/how-it-works/index.html`
in a browser to scrub through it scene by scene (tick loop, planner, scanner + A*, survival, water, task tree).
The mod icon (`src/main/resources/assets/autocraft/icon.png`, enlarged in `docs/logo-512.png`) is a player head
that is half face, half circuitry.

## Stack
- Minecraft **26.3**, Fabric Loader 0.19.5, Fabric API, Java 25, Loom (`./gradlew build`, `./gradlew runClient`).
- Mojang names (26.x is unobfuscated — no mappings).
- No external AI libs: the neural net is a tiny MLP in plain Java. Recipes come from the game jar's own data.

## Control panel (key **K**, rebindable under Controls → autoCraft)
- **Tasks**: everything the bot is asked to do, as one tree. Left: the tree — item icons, a status dot per task
  (green done, blinking amber running, white next, gray later, dark = waits for its subtasks), fold arrows,
  guide lines from subtasks to their parent, progress bars (collected or carried / amount, subtasks done, blocks
  placed); hover a row for what it does and why it waits. Right, *Add items*: search every item the bot can get
  (blue corner = made: crafted, smelted, hunted or by a procedure; the tooltip says how), click to add it with
  the amount in the box (right-click: a stack, Enter: the typed name, also groups like `log`) under the selected
  task, or at the top with nothing selected; adding an item again raises its amount.
  Right, *Edit task* (opens when a row is clicked): title, item and amount or schematic (*From placement* takes
  the ghost's spot and rotation; otherwise a spot is found when it runs), context notes; *Expand* breaks a task
  into its parts (a crafted item's ingredients, a build's materials); *Up*/*Down* reorder, *Delete* removes. On
  collect tasks *Source* switches between gathering and **getting it from a base** (a named base, or empty = the
  nearest base whose chests have it), e.g. Expand a build, then mark the materials you keep at base. *+ Group* /
  *+ Build* add under the selection, *Clear done* tidies. *Run all* works through the tree (subtasks before their
  parent, one job, fetch or build at a time), *Run selected* only that task and its subtasks; stopping the bot
  pauses the run. Saved per world in `config/autocraft/tasktree.json`.
- **Build**: schematic list (built-ins + `config/autocraft/schematics/`, *Open folder*, *Reload*), size and
  material preview; *Place in world* (ghost at the crosshair) or *Find a spot* (bot-picked ground, then
  adjust). While placing, the tab becomes placement settings: X/Y/Z with −/+ (shift: 8), rotation, mirror,
  *Move to me*, *To crosshair*, *Materials...*, *Cancel*, *In world*, *Build*.
- **Materials** (from the Build tab): wood type (auto = what grows here, or a chosen wood), a stand-in for
  structural wood (auto = only where no trees grow, e.g. sandstone in deserts), filler blocks for cells nobody
  sees, and explicit block replacements (pick a schematic block, then any block). The ghost updates live;
  *Save as default* stores the choices in `config/autocraft/materials.json`.
- **Team**: host a team (shows the address and a 5-character code) or join one; lists members and their roles.
- **Bot**: live state, job progress bars, build progress, current prerequisite, health/food/position, last death;
  *Pause/Resume*, *Stop*, *Add base here* / *Remove nearest*, the list of bases and of built structures.
- **Settings** (saved to `config/autocraft/settings.json`): log off when done, status overlay, smooth aim, after
  death recover items and continue, log off when another player comes near (and how near), materials at base,
  resume after relog.
- A small status overlay (top left) shows activity and progress bars while the bot runs.
- The bot keeps working while the panel is open (it isn't a pause screen).

## Placing schematics (Litematica-style)
The schematic shows in the world as translucent blocks plus a cyan bounding box while you position it; once
confirmed the box turns green and the ghost keeps showing what's left to build. Blocks already correct are
hidden, blocks in the way get red outlines, water/lava and chest-like blocks get colored outlines.

| Key (rebindable) | While placing |
|---|---|
| ↑ ↓ ← → | move away from / toward you, left / right (sneak: 8 blocks) |
| Page Up / Page Down | move up / down |
| Left Alt + mouse wheel | nudge toward / away from where you look |
| R (sneak: other way) | rotate 90° |
| M | mirror: none → left-right → front-back |
| G | move it to the block you look at (footprint centered) |
| Enter | build it |
| Backspace | cancel |

## Commands (typed in chat, never sent to the server)
| Command | Effect |
|---|---|
| `!ac collect <item> <amount> [<item> <amount> ...]` | start a job (`diamond 10 iron 32 torch 64 piston 8`); crafted items are planned through the recipe book |
| `!ac place <schematic>` | show it as a ghost at the crosshair and position it with the keys above |
| `!ac build <schematic> <x> <y> <z> [0\|90\|180\|270]` | build right away with its corner at x y z (`~` = relative), optionally rotated |
| `!ac build <schematic> here [rotation]` | same, corner at your feet |
| `!ac farm <resource> [<x> <y> <z> \| here] [rotation]` | build a farm for a resource (site chosen automatically without a position) |
| `!ac schematics` | list built-in and user schematics, show the schematic folder |
| `!ac built` | list everything the bot has built (farms are reused by jobs) |
| `!ac stop` / `!ac pause` / `!ac resume` | control the job |
| `!ac status` | progress, current behavior, build, prerequisite, health/food |
| `!ac why` | what it's doing and for which prerequisite, plus its last decisions (behavior, status, need), to see why it stalls or wanders |
| `!ac base [name]` | add a base where you stand, or move the base with that name (first one is "home"); chests within 8 blocks are its storage |
| `!ac base remove <name>` / `!ac bases` | remove a base / list them |
| `!ac base stocked on\|off` | the base chests hold materials: check them before gathering, even unseen ones |
| `!ac team host` / `!ac team join <host[:port]> <code>` / `!ac team leave` | team mode (see below) |
| `!ac logoff on\|off` | disconnect when the job is done (default on) |
| `!ac goto <x> <y> <z>` | debug: pathfind to a block |
| `!ac resources` | list named resources (any natural block's item id, craftable/smeltable item, mob drop or procedure item also works) |

## Architecture
One client-tick loop. Every tick the `Bot` releases all inputs, then asks behaviors **in priority
order**; the first that returns `true` owns the tick (subsumption). Lower behaviors keep their state
and resume when higher ones let go. Planners that need materials (tools, build materials, crafted job items,
portal obsidian) post a **need** on the bot; the collector serves needs gatherable in the current dimension
first, the portal behavior travels for the rest. Elytra flights take over the loop (only the lava/fall
reflexes can interrupt) until landing.

```mermaid
flowchart TD
  T[client tick] --> R0[LavaReflex: swim out toward solid ground, fire resistance]
  R0 --> RD[DrowningReflex: surface for air]
  RD --> R1[FallReflex: lava landing: steer, water/block into the lava; high fall: water clutch]
  R1 --> R2[DefenseReflex: shield arrows, deflect/dodge ghast fireballs, back off lit creepers]
  R2 --> R3[EatReflex]
  R3 --> R4[EffectReflex: milk / honey]
  R4 --> C[Combat: quick kills only, jump crits; hunted mobs fought on purpose; flee at low health, then keep away and heal before work resumes]
  C --> RC[Recover: after a death, back to the spot for the dropped items]
  RC --> M[Mending: swap damaged mending gear into the offhand while XP is around]
  M --> GO[Goto: debug walk]
  GO --> F[Finish: goal met -> pick up stations, store + log off]
  F --> G[Gear: armor, shield, tools scaled to the work (needed tools may use job loot: 3 mined cobblestone = stone pickaxe), shears/sword when needed, rockets, nether kit]
  G --> FO[Food: hunt animals when hungry and food is low]
  FO --> ST[Storage: junk drop, bundles, shulkers, base chests]
  ST --> B[Build: schematic orders, materials (window gathered before placing), big-build stash, team roles]
  B --> FA[Farm: harvest registered farms (mob farms for hunted drops), auto-build one when searching fails]
  FA --> MK[Make: craft/smelt/cut/run procedures for requested items, ask for raw materials and hunts]
  MK --> P[Portal: weigh known portal / bucket portal / mined obsidian by time, change dimension]
  P --> CO[Collect: base chests when quicker, else scan -> path -> mine/hunt -> pick up, explore by biome / structure / boat / elytra]
```

| Package | Responsibility |
|---|---|
| `com.autocraft` | entrypoint, `Bot` (job, build order, needs, behavior loop, flights, death and respawn, player alert), `Settings`, `TaskSave` (resume after relog), `DeathLog`, chat logging |
| `command` | `!ac` chat command parser |
| `goal` | `Job` (+ skipped and deferred requirements), `Requirement` (mined or hunted: items, sources, mobs, tool, strategy, Y level, dimension, biomes; or a made product), `Resources` registry |
| `tasks` | `TaskTree` + `TaskNode`: the user's task tree and its runner |
| `world` | `WorldScanner` (exact nearest-k block scan: rings outward, chunks/sections that can't beat the k-th nearest skipped; entities), `BlockUtil`, `BiomeFinder`, `ChestMemory` (what chests hold, per world), `WorldKey` |
| `control` | `Controls` (keys, mine/attack/place/use), `Aim` (Bezier view turns, movement remapped while turning), `InvUtil`, `ContainerOpener`, `Placement` |
| `path` | incremental A* `Pathfinder` (walk, diagonal, jump-up, staircase, safe fall, dig-down, pillar, bridge, mine-through, swim), goals, executor, `Navigator` |
| `behavior` | the priority behaviors above + `Harvest`, `BaseDeposit`, `BaseFetch` |
| `combat` | `Threats` (who's dangerous), `Melee` (weapon choice, approach, crits, strike timing) |
| `ai` | `ThreatNet` MLP + online training, persisted to `config/autocraft/threatnet.json` |
| `craft` | `RecipeBook` (all vanilla crafting/smelting/stonecutting recipes from the game data), cost-based `Planner` (craft / smelt / cut / mine / hunt / procedure), `Gatherable`, `Stations` (shared table/furnace/stonecutter rules, base stations, pickup), `Workshop` (crafter + multi-furnace smelter + `Cutter` + procedures) |
| `loot` | `LootBook`: block, mob, chest and archaeology loot tables from the game data (chances, shears/silk-touch conditions); `LootSites`: where each structure is and how to recognise it |
| `hunt` | `MobHints`: where each mob lives (dimension, biomes, tell-tale structure blocks) |
| `procedure` | non-recipe items: `BeeNestProcedure` (honey bottles, honeycomb), `AnimalProcedure` (milk, wool, scutes, eggs), `BlockWorkProcedure` (stripped logs, carved pumpkins, mud, concrete), `OxidizeProcedure` (copper garden), `TrialChamberProcedure` (trial keys, ominous bottles, ominous keys, heavy core), `ChestLootProcedure` + `SculkGuard` (structure loot, quiet ancient cities) |
| `schematic` | `.litematic` / `.schem` / `.nbt` loading, built-in designs, `SchematicLibrary` (built-ins + user folder) |
| `build` | `BuildPlan` (mirror, rotation, substitution, ordering, bill of materials), `Substitution` + `MaterialSettings` + `MaterialAdvisor` (wood swaps, stand-ins, filler, replacements), `Placement` (a schematic being positioned), `PlacementSolver` (orientation), `Builder`, `SiteFinder`, `FluidFill`, `BaseWithdraw`, `DepotDeposit`, `StructureRegistry` (built + protected areas) |
| `team` | `Team` (roles, splitting work, schematic sharing), `TeamLink` (newline-delimited JSON over TCP), `TeamRole` |
| `travel` | boats over water/ice, elytra flights, nether portals (find, build, light, enter, return; `PortalOptions` time costs, `CastPortalBuilder` bucket portal) |
| `ui` | `AutoCraftScreen` control panel, `TaskTreePanel`, `MaterialsScreen`, `HudOverlay` status overlay, `ItemCatalog` (what jobs can ask for), `Placements` (placement keys), `GhostRenderer` (in-world schematic preview) |
| `mixin` | keep the bot's block breaking alive; Alt + wheel nudges a placement instead of switching hotbar slots; per-frame aim updates |

### Neural net (ThreatNet)
Inputs: own health, armor, best weapon damage, shield, food, hostile count, nearest distance, summed
mob danger, creeper nearby, ranged count, flying shooters, night/nether. 12 tanh hidden units, sigmoid
output = “fighting is a good idea”. Bootstrapped on synthetic samples from a hand-written teacher so it
is sane on first run, then nudged online after every fight (won without losing >50% health → 1,
else → 0; ~10 consistent outcomes flip a decision) and saved.

### Schematics
- Put `.litematic` (Litematica), `.schem` (Sponge/WorldEdit) or `.nbt` (structure block) files in
  `config/autocraft/schematics/`, then place them from the panel (K → Build) or with `!ac place <file name>`.
- The builder clears the space, then places full blocks bottom-up, then partial/attached blocks, water,
  lava and finally plants. Orientation (stairs, logs, hoppers, observers, doors...) is solved by asking
  the block's own placement logic which view angle and clicked face give the wanted state.
- Missing materials come from base chests first (those known to hold the item, or a first look when the base
  chests haven't been seen), then the recipe planner (craft/smelt/cut/mine/hunt/procedure, including trips to
  the Nether, e.g. quartz for observers). A whole window of materials is gathered before placing resumes.
  Items nothing can produce (End items) are skipped with a message — put them in a base chest to include them.
  Blocks whose material takes time (oxidized copper) are placed last.
- Big builds (bill over 27 stacks): spare materials go to the base chests when the base is within 64 blocks,
  otherwise into a chest the bot puts down beside the site (crafted if needed), and come back out as needed.
  Pickaxe/shovel/axe tiers are chosen for the build's whole bill and the digging the site needs.
- Farms: name a file `farm-<resource>-<anything>.litematic` and jobs for that resource can build and use
  it (`farm-hostile_mobs-…` for a mob farm). Automatic farms (with chests/barrels) are emptied every couple of
  minutes; manual cane/tree/crop farms are harvested and replanted.
- Built-in: `starter_base` (7×7 hut with crafting table, furnace, double chest; becomes the base),
  `cobblestone_generator`, `sugar_cane_farm`, `tree_farm`, `wheat_farm`, `mob_farm` (dark room 26 blocks up
  over a drop shaft: zombies, skeletons, creepers, spiders, witches and endermen die from the fall onto a lit
  landing; the bot waits in the doorway, finishes off survivors and picks up drops; ~670 cobblestone).
  Community farm schematics found online (redstone-schematics.org, Planet Minecraft, GitHub archives) mostly
  carry no clear license, so none are bundled — download the ones you like into the folder.

### Mob drops, loot and special procedures
- Mining loot and mob drops are read from the game's loot tables: flint from gravel, string from spiders (or
  cobwebs without shears), gunpowder from creepers, ender pearls from endermen, blaze rods in fortresses.
  The planner compares mining, hunting, crafting and procedures by effort and takes the cheapest.
- Hunting: chase and kill the nearest adult, unnamed mob of the wanted kind, pick up the drops; otherwise head
  for tell-tale structure blocks (nether bricks for blazes) or explore toward the mob's biomes. Gear makes a
  sword first. A mob farm nearby is used for its mobs' drops; when hunting keeps coming up empty (daytime), the
  built-in mob farm is built (`!ac farm hostile_mobs` builds it on request).
- Procedures (`procedure` package), each run until the amount is carried:
  - honey bottles (glass bottle) and honeycomb (shears) from full bee nests/hives;
  - milk (bucket on a cow/mooshroom/goat), wool (shears; dyed sheep or white wool + dye, whichever is cheaper),
    armadillo scutes (brush), eggs / brown eggs / blue eggs (wait by chickens of that variant);
  - stripped logs/wood (axe), carved pumpkins (shears), mud (water bottle on dirt), concrete (powder next to
    water; a bucket is poured and scooped back if no water is near);
  - exposed / weathered / oxidized copper: a copper garden by the base (or here), remembered in
    `config/autocraft/copper_garden.json`. Copper oxidizes only in loaded chunks (about an hour per stage), so
    the rest of the job goes first and the bot then waits by the garden; overshot blocks are scraped back;
  - waxed copper comes from the normal honeycomb recipe;
  - trial chambers: tunnel at Y −30 to find one, beat trial spawners (their mobs become hunt targets), collect
    the keys, open vaults (ominous bottles come from normal vaults); for ominous keys and the heavy core,
    drink an ominous bottle, walk in for Trial Omen, beat ominous spawners and open ominous vaults. Opened
    vaults are remembered in `config/autocraft/trial_chambers.json`; a spent chamber means searching for the next.
- A procedure that fails twice for an item is dropped for the rest of the job (the planner falls back or skips).

### Team mode
- One computer hosts (`!ac team host` or the Team tab): the mod opens a small TCP server on port 25590 and
  shows a 5-character code. Others join with `!ac team join <host[:port]> <code>` (127.0.0.1 on the same PC).
- Builds: the host places, one member digs out the site, the rest gather materials and bring them to a depot
  chest next to the build. Collect jobs are split by amount across members.

### Structure loot
Items that only come from where they generate are job targets too (the item picker says "Structure loot"): the
loot tables in the game jar give each structure's chance per chest, and the planner picks the structure with the
lowest effort ÷ chance. The bot searches for it (tunnelling at the structure's depth or walking toward its biomes;
an explorer map in the inventory pointing at one is followed first), opens its containers one by one and takes the
item. Opened containers and finished structures are remembered per world (`config/autocraft/structure_loot.json`);
chests at your bases are never touched. Structures in the Nether trigger a portal trip (with the Nether kit).

| Structure | Holds | Unique there (examples) |
|---|---|---|
| ancient city (quiet mode) | chests | echo shards, disc fragment 5, ward/silence trims |
| bastion | chests | netherite upgrade, pigstep, snout trim |
| nether fortress | chests | rib trim |
| stronghold | chests | eye trim |
| desert pyramid | chests (TNT trap avoided) + suspicious sand | dune trim, archer/miner/prize/skull sherds |
| jungle temple | chests (tripwires avoided) | wild trim |
| shipwreck | chests | coast trim, buried treasure maps |
| buried treasure | chest dug up at the map's X | heart of the sea |
| pillager outpost | chest | sentry trim, goat horns |
| woodland mansion | chests | vex trim |
| mineshaft | chest minecarts | music disc "bounce" |
| trial chamber | chests/barrels (vaults: own procedure) | flow/guster patterns, creator/precipice discs |
| abandoned camp | chests/barrels | explorer maps to other structures |
| desert well, ocean ruins, trail ruins | suspicious sand/gravel (brushed) | sherds, sniffer egg, relic disc, wayfinder/raiser/shaper/host trims |
| dungeon, ruined portal, village | chests | enchanted golden apples, music discs, horse armor... (also elsewhere) |

Left out because nothing in them is unique: igloos, underwater ruin chests (their maps also come from shipwrecks),
the spawn bonus chest. End cities (elytra, spire trim) would need End travel, which the bot doesn't do.

**Traps**: the pathfinder never walks into pressure plates or tripwire (desert pyramid TNT, jungle temple arrows);
it digs around them instead.

**Ancient cities** (kit: 24 wool, plus the usual food and weapon). Once sculk is within 12 blocks the bot goes
quiet:
- it sneaks everywhere (footsteps, landings and swimming make no vibrations while sneaking) and never sprints;
- it never steps on sculk sensors or shriekers (that sets them off even when sneaking);
- it mines and places nothing but wool (wool makes no vibrations and blocks them);
- it eats only when health or food get low (eating is heard);
- before opening a chest — always heard — it puts wool on the line between the chest and every sculk sensor in
  range, or leaves that chest.

Shrieks are counted: on the third (the fourth summons the warden) it walks back out the way it came and waits
10 minutes for the warning level to drop. A warden in sight means run (no sneaking) and wait 20 minutes; the bot
never fights one.

### Getting to the Nether
Before travelling, rough time costs are compared and announced: walking to a remembered or visible portal, a
**bucket portal** (no diamond pickaxe: at a lava pool, each frame block is cast in place — water poured into the
cell, scooped back, lava poured in while the spread water still touches it; the lower four from the ground,
the upper six from a 3-block pillar; needs a water bucket and up to 6 lava buckets, made from iron when
missing), or 10 obsidian mined (or cast from lava and mined) with a diamond pickaxe and built. A bucket portal
that fails twice falls back to obsidian.

### Recipes: crafting table vs stonecutter
Stonecutter recipes are in the planner too: one click, better yields for stairs/walls (1:1 instead of 6:4),
but a stonecutter costs an iron ingot, so it wins when one is nearby (or at the base) or when the savings pay for
making one.

### Smooth aim
With *Smooth aim* on (default) the view never snaps: each new look target starts a cubic Bezier curve from the
current view with slightly bent control points and a minimum-jerk speed profile, 60–260 ms depending on the
angle; small target moves re-aim the running curve. Movement keys are remapped while the head is still turning
so the bot walks where it means to. Clicks whose result depends on facing (stairs, observers) wait until the turn
has settled and reached the server; untargeted item use (buckets, food) finishes the turn first.

### Deaths, other players, relogs
- Every death is logged (`config/autocraft/deaths.json`, last one shown on the Bot tab). With *After death:
  recover* on, the bot respawns, goes back (through a portal if needed) and picks up what's lying there, then
  carries on; burnt items (lava, fire) are written off. Off: the job stops.
- *Log off near players*: another player (not a teammate) within the radius ends the session.
- The task in progress (job with deposited counts and skipped items, build order, recovery) is saved per world
  in `config/autocraft/tasks.json` every few seconds and resumed a few seconds after joining again.
- Bases: as many as you like per world (`config/autocraft/bases.json`), e.g. home, a nether base, a mining
  outpost. Deposits, base furnaces/tables and returning after a job use the nearest base in the current
  dimension (home when there's none).
- Chest memory: every chest or barrel screen that opens — the bot's or yours — is read and remembered per world
  (`config/autocraft/chests.json`). Prerequisites a base's chests hold are fetched from the quickest such base
  when the round trip beats gathering, builds take from the base nearest the site that has the item; *Materials
  at base* (or `!ac base stocked on`) checks base chests not looked into yet.
- "Per world" means the singleplayer save folder or the server address, plus the world's hashed seed (which the
  server sends every client): two singleplayer worlds both called "New World", or two worlds behind one address,
  never share bases, task tree, structures, chests, deaths, copper garden, used vaults or resume state. A task-tree
  run belongs to the world it was started in and waits while you're elsewhere. (Files from before this keying
  start over.)

### Water and wood
- Swimming: on a straight stretch of deep water with food to spare the bot sprint-swims, skimming the surface
  (it sinks under once to start swimming, then looks slightly up so it rides at the top with its head clearing
  the water, keeping its air full); short of air it paddles with its head up until the bar is full. Shallow
  water is waded, dives to sunken blocks swim straight at them, and it stands up out of the swim to climb a bank.
  Paths cost deep water like walking when it can sprint-swim, and the bot doesn't re-plan for bobbing up and down.
- An explore leg over water or ice, or 20 seconds of open-water swimming, makes the gear planner build a boat
  (the cheapest kind, or whichever kind 5 planks are carried for); legs over water are then sailed.
- With wood needed and no trees around (out at sea, an iceberg, or exploring came up empty), shipwreck planks and
  logs count as wood — planks only in or under water, never from someone's house.
- Crafted or smelted blocks (iron block, comparator, iron bars, terracotta) are never searched for in the world:
  mining one would give it back, but none generate, so they're always made.

## Phases
| # | Phase | Status |
|---|---|---|
| 1 | **Skeleton** — Gradle/Loom project, entrypoint, `!ac` commands, `Bot` loop, logging | code complete |
| 2 | **Movement** — `Controls`, `BlockUtil`, incremental A* + executor, `!ac goto` | code complete |
| 3 | **Collection** — registry, scanner, mine + pick up, explore (ore Y level + tunnel; surface walk), log off | code complete |
| 4 | **Survival** — lava escape, fall/lava clutch, eating, hunting, milk, shield/back-off, ThreatNet combat | code complete |
| 5 | **Tools & crafting** — pickaxe tiers, data-driven recipe planner, 2x2 + table crafting, smelting, armor/shield, bucket | code complete |
| 6 | **Storage** — junk dropping (never job loot), bundles, shulker boxes, base chests with deposited counts | code complete |
| 7 | **Nether** — kit (gold boots, flint and steel, iron pickaxe, bucket, blocks, extra food); obsidian mined or cast; portals built/reused and remembered; trips home for materials or base storage; nether mob rules | code complete |
| 8 | **Travel** — boats over water/ice; elytra flights with rocket crafting and durability checks | code complete |
| 9 | **Biomes & mending** — biome-directed exploration; mending gear repaired in the offhand | code complete |
| 10 | **Schematics & farms** — user schematics, built-in starter base and farms, farm operation and auto-build | code complete |
| 11 | **Crafted jobs & UI** — any craftable/smeltable item as a job target (unobtainable ones skipped); control panel (K) and status overlay | code complete |
| 12 | **Litematica-style placement** — ghost preview in the world, keyboard/mouse positioning, rotate + mirror, build progress overlay | code complete |
| 13 | **Materials & team** — substitution (wood swaps, stand-ins, filler, replacements); team mode over TCP with build roles and split jobs | code complete |
| 14 | **Fixes** — shared station rules, multi-furnace smelting, station pickup, faster pathing, quick-kill combat with crits, drowning reflex, tool tiers scaled to work | code complete |
| 15 | **Mob drops & procedures** — loot tables, hunting, honey/wool/milk/eggs, block work, copper garden, trial chambers | code complete |
| 16 | **Additions** — smooth aim, mob farm, stonecutter, portal time costs + bucket portal, death recovery, player alert, big-build stash and tool investment, chest memory and base fetches, resume after relog, task tree | code complete |
| 17 | **Hardening** — in-game testing; fixed so far: stalls (gear planning was blocked by any mob within 8 blocks, worn cheap tools refused, job loot never used for tools), far targets past chunk borders, sunken logs chosen over dry ones, hurt flee/work ping-pong, eating blocked by mobs behind walls, searching for crafted blocks (iron block for an iron pickaxe), saves leaking between same-named worlds, slow/re-planning swimming (now sprint-swims skimming the surface); boats over water, shipwreck wood; `!ac why` decision trace | in progress |

"Code complete" = compiles against the real 26.3 client jar; not yet run in-game (see Build).

## Build & test
- `./gradlew build` → `build/libs/autocraft-<version>.jar` (drop into `.minecraft/mods` with Fabric API).
- `./gradlew runClient` starts a dev client; press K, or try `!ac goto`, `!ac build starter_base here`,
  `!ac collect log 16`, `!ac collect iron 8`, `!ac collect torch 32`, `!ac collect ender_pearl 4`,
  `!ac collect honeycomb 6`, `!ac farm cobblestone`.
- Loom must download Minecraft's libraries from `libraries.minecraft.net`; build on a network that allows it.

## Known limits
- Servers with anti-xray or strict anti-cheat will fight the bot (scanning sees hidden ores; reach and timing are
  vanilla but not humanized beyond the aim curves).
- End travel, enchanting, villager trading and smithing recipes are out of scope.
- Chest-only loot from End cities isn't reachable (no End travel). The mob farm gives no player-kill-only drops
  (spider eyes) and spawns slowly when caves nearby compete for mob spawns.
- Lava escape needs solid ground within ~4 blocks (or the last safe spot) or a fire resistance potion; a fall
  into a wide lava lake with no water bucket, no blocks and no solid edge in reach can still be fatal.
- Builder limits: block entity contents (chest items, sign text) aren't recreated; waterlogging isn't
  placed.
