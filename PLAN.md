# autoCraft — build plan

Client-side Fabric mod. You give it items + amounts — mined resources, mob drops, anything craftable/smeltable,
or things that need a procedure (honey, wool, oxidized copper, trial keys, the heavy core); it survives, paths,
mines, hunts, crafts tools and the requested items, travels to the Nether when a resource lives there, builds
farms or your own schematics (alone or as a team of bots), stores loot and logs off when the goal is met.

**How it works, animated:** `docs/how-it-works/how-it-works.mp4` (2.6 min), or open `docs/how-it-works/index.html`
in a browser to scrub through it scene by scene (tick loop, planner, scanner + A*, survival, water, task tree).
**Explainer video** (1:52, silent so a song can be laid over it): how to use it (K, pick an item, Run) and what it
then does on its own — the plan, x-ray scan and A*, survival at night, ancient city loot, a team build, the end card.
Generated entirely by the scripts in `docs/video/` with original textures; `python docs/video/render_all.py` renders
it (Blender 5.x) to `docs/video/autocraft-explainer.mp4`. Stills of every shot are in `docs/video/previews/`; the
shot list and options are in `docs/video/storyboard.md`. **In game**, `!ac film play` stages and plays the same eight
shots in a singleplayer world (your skin, your shader pack) for screen recording; see the storyboard's "In game" part.
The mod icon (`src/main/resources/assets/autocraft/icon.png`, enlarged in `docs/logo-512.png`) is a gear that
dissolves into redstone circuitry: its right-hand teeth turn into traces ending in pads, and bits break off it.

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
  **Plans:** every collect task carries the bot's own plan for it as folded steps, from the same planner the job
  uses — what it mines, crafts or smelts, the pickaxe it makes before mining, crafting table, furnace and fuel
  (32 diamond: mine diamond ore ← iron pickaxe ← smelt iron ← mine iron ore ← stone pickaxe ← cobblestone ←
  wooden pickaxe ← planks ← chop trees). Each step shows how it's got and how much is carried; green = have it,
  white = can be done now, dark = after the steps under it; parts already shown once read "see above". Steps are
  read-only and never run on their own (the task runs the whole plan as one job); the plan of the running task
  follows the bot every few seconds, *Plan* refreshes any other.
  Right, *Edit task* (opens when a row is clicked): title, item and amount or schematic (*From placement* takes
  the ghost's spot and rotation; otherwise a spot is found when it runs), context notes; *Plan* (collect tasks)
  or *Expand* (builds: their materials as collect subtasks); *Up*/*Down* reorder, *Delete* removes. On
  collect tasks *Source* switches between gathering and **getting it from a base** (a named base, or empty = the
  nearest base whose chests have it), e.g. Expand a build, then mark the materials you keep at base. Every task
  (groups too) has an **ending**: *Ends: where it is* / *Ends at: <base>* (click through the bases) and, at that
  base, *Just go there* / *Store: emptiest* / *Store: similar* (see "Ending at a base"). *+ Group* / *+ Build* /
  *+ Go to* add under the selection, *Clear done* tidies. A **go-to task** heads for *Coords* (X, Y — empty =
  any height on that column — Z; *In: current dim / overworld / nether*; *Use my position*), a *Base* (click
  through the bases) or a *Structure* (click through the kinds: the nearest one in sight, else searched for); its
  row shows a compass, and while it runs the details read what the bot is doing. *Run all* works through the tree
  (subtasks before their parent, one job, fetch, build or go-to at a time), *Run selected* only that task and its
  subtasks; stopping the bot pauses the run. Jobs started with `!ac collect` and go-tos started with `!ac goto` run
  as tasks here too, and a job or go-to resumed after joining shows as the running task (its unfinished task, or a
  new one), so the plan and progress of every job are on this tab. Saved per world in
  `config/autocraft/tasktree.json`.
- **Build**: schematic list (built-ins + `config/autocraft/schematics/`, *Open folder*, *Reload*), size and
  material preview; *Place in world* (ghost at the crosshair) or *Find a spot* (bot-picked ground, then
  adjust). While placing, the tab becomes placement settings: X/Y/Z with −/+ (shift: 8), rotation, mirror,
  *Move to me*, *To crosshair*, *Materials...*, the ending (*Ends at* / store choice, as on tasks), *Cancel*,
  *In world*, *Build*.
- **Materials** (from the Build tab): wood type (auto = what grows here, or a chosen wood), a stand-in for
  structural wood (auto = only where no trees grow, e.g. sandstone in deserts), filler blocks for cells nobody
  sees, and explicit block replacements (pick a schematic block, then any block). The ghost updates live;
  *Save as default* stores the choices in `config/autocraft/materials.json`.
- **Team**: host a team (shows the address and a 5-character code) or join one; lists members and their roles.
- **Bot**: live state, job progress bars, build progress, go-to destination, the base the task ends at, current
  prerequisite, health/food/position, last death; *Pause/Resume*, *Stop*, *Add base here* / *Remove nearest*, the
  list of bases and of built structures.
- **Settings** (saved to `config/autocraft/settings.json`): log off when done, status overlay, smooth aim, after
  death recover items and continue, log off when another player comes near (and how near), materials at base,
  resume after relog, respawn beds (and the distance that counts as far); *Set spawn point here*.
- A small status overlay (top left) shows activity and progress bars while the bot runs, the go-to destination
  (with the distance left) and the base the task ends at.
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
| `!ac collect <item> <amount> [<item> <amount> ...] [then <base> [go\|emptiest\|similar]]` | add the items to the task tree and run them: one collect task, or several in a group collected one after another (`diamond 10 iron 32 torch 64 piston 8`); an unfinished task for the same item and amount is reused. Whatever the bot was doing stops. Crafted items are planned through the recipe book. `then …`: end at that base (see "Ending at a base") |
| `!ac goto <x> [<y>] <z> [overworld\|nether] [then …]` | go-to task to coordinates (`~` = relative; no y = any height on that column; no dimension = this one), run from the task tree |
| `!ac goto base <name> [then …]` / `!ac goto structure <kind> [then …]` | go-to task to a base, or to the nearest structure of a kind (`village`, `fortress`, `ancient_city`...; `!ac goto structure` lists them) |
| `!ac place <schematic>` | show it as a ghost at the crosshair and position it with the keys above |
| `!ac build <schematic> <x> <y> <z> [0\|90\|180\|270] [then …]` | build right away with its corner at x y z (`~` = relative), optionally rotated |
| `!ac build <schematic> here [rotation] [then …]` | same, corner at your feet |
| `!ac farm <resource> [<x> <y> <z> \| here] [rotation] [then …]` | build a farm for a resource (site chosen automatically without a position) |
| `!ac schematics` | list built-in and user schematics, show the schematic folder |
| `!ac built` | list everything the bot has built (farms are reused by jobs) |
| `!ac stop` / `!ac pause` / `!ac resume` | control the job |
| `!ac status` | progress, current behavior, build, prerequisite, health/food |
| `!ac why` | what it's doing and for which prerequisite, what the gear planner last decided ("tool for iron: gather 16 cobblestone"), plus its last decisions (behavior, status, need), to see why it stalls or wanders |
| `!ac base [name]` | add a base where you stand, or move the base with that name (first one is "home"); chests within 8 blocks are its storage |
| `!ac base remove <name>` / `!ac bases` | remove a base / list them |
| `!ac base stocked on\|off` | the base chests hold materials: check them before gathering, even unseen ones |
| `!ac team host` / `!ac team join <host[:port]> <code>` / `!ac team leave` | team mode (see below) |
| `!ac logoff on\|off` | disconnect when the job is done (default on) |
| `!ac spawn` | where the bot thinks the respawn point is (and how far), the respawn bed setting, the beds it put down in this world |
| `!ac spawn here` / `!ac spawn <x> <y> <z>` | set the respawn point (this dimension) when the bot couldn't see it set: a bed slept in before, `/spawnpoint` |
| `!ac spawn beds on\|off [blocks]` | respawn beds (default off), optionally with the distance that counts as far (default 256) |
| `!ac resources` | list named resources (any natural block's item id, craftable/smeltable item, mob drop or procedure item also works) |
| `!ac film play [all \| 1-8 \| name…]` / `!ac film record [60fps] [shots…]` / `!ac film list` / `!ac film stop` | build the sets here and play the explainer video in this singleplayer world, live or saved to `autocraft-film/` as an MP4 at an exact frame rate (throwaway world; see `docs/video/storyboard.md`) |

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
  R1 --> R2[DefenseReflex: shield toward shooters about to fire (bow drawn, crossbow loaded) and their arrows, chasing a hittable one while it reloads, leaving a melee mob at hand to combat; deflect/dodge ghast fireballs, back off lit creepers]
  R2 --> R3[EatReflex]
  R3 --> R4[EffectReflex: milk / honey]
  R4 --> C[Combat: fight / pillar / flee by a damage race + ThreatNet; trained fight moves in open close fights; hunted mobs fought on purpose; lie low and heal before work resumes]
  C --> RC[Recover: after a death, back to the spot for the dropped items]
  RC --> M[Mending: swap damaged mending gear into the offhand while XP is around]
  M --> F[Finish: the task's goal met -> pick up stations; ending at a base: go there (portal first), store by the chosen rule; stop + log off]
  F --> G[Gear: armor, shield, tools scaled to the work (needed tools may use job loot: 3 mined cobblestone = stone pickaxe), shears/sword when needed, rockets, nether kit]
  G --> FO[Food: hunt animals when hungry and food is low]
  FO --> RB[Bed: far from the spawn point with a death likely -> bed down near the work, spawn point set]
  RB --> ST[Storage: junk thrown out of reach (surplus blocks first), bundles, shulkers, base chests]
  ST --> B[Build: schematic orders, materials (window gathered before placing), big-build stash, team roles]
  B --> FA[Farm: harvest registered farms (mob farms for hunted drops), auto-build one when searching fails]
  FA --> MK[Make: craft/smelt/cut/run procedures for requested items, ask for raw materials and hunts]
  MK --> P[Portal: weigh known portal / bucket portal / mined obsidian by time, change dimension]
  P --> CO[Collect: base chests when quicker, else scan -> path -> mine/hunt -> pick up, explore by biome / structure / boat / elytra]
  CO --> GO[Goto: go-to task: coordinates / base / nearest structure (in sight, else explored for) -> arrived]
```

| Package | Responsibility |
|---|---|
| `com.autocraft` | entrypoint, `Bot` (job, build order, go-to destination, the base a task ends at, needs, behavior loop, flights, death and respawn, player alert), `Settings`, `TaskSave` (resume after relog), `DeathLog`, `SpawnPoint` (respawn point and our beds, per world), chat logging |
| `command` | `!ac` chat command parser |
| `goal` | `Job` (+ skipped and deferred requirements), `Requirement` (mined or hunted: items, sources, mobs, tool, strategy, Y level, dimension, biomes; or a made product), `Resources` registry, `Destination` (where a go-to task heads), `Ending` (the base a task ends at, and how it stores there) |
| `tasks` | `TaskTree` + `TaskNode`: the user's task tree and its runner |
| `world` | `WorldScanner` (exact nearest-k block scan: rings outward, chunks/sections that can't beat the k-th nearest skipped; entities), `BlockUtil`, `BiomeFinder`, `ChestMemory` (what chests hold, per world), `WorldKey` |
| `control` | `Controls` (keys, mine/attack/place/use), `Aim` (Bezier view turns, movement remapped while turning), `InvUtil`, `ContainerOpener`, `Placement` |
| `path` | incremental A* `Pathfinder` (walk, diagonal, jump-up, staircase, safe fall, dig-down, pillar, bridge, mine-through, swim), goals, executor, `Navigator`, `AvoidedMoves` (moves the executor failed are routed around for 2 minutes), `DigTime` (digging on the planned path, with the tools carried and with a cheap pickaxe) |
| `behavior` | the priority behaviors above + `Harvest`, `BaseDeposit` (storing at a base: nearest, emptiest or similar-items chest), `BaseFetch`, `BedSite` (where a respawn bed fits) |
| `combat` | `Threats` (who's dangerous, a mob's real hit: base + weapon by difficulty, shield-breaking axes), `Melee` (weapon choice, approach, crits, strike timing), `Guard` (shield) |
| `ai` | `ThreatNet` MLP + online training, persisted to `config/autocraft/threatnet.json`; `CombatPolicy` (fight moves trained in `tools/combatsim`) + `CombatObs` (its input, built like the simulator's) |
| `craft` | `RecipeBook` (all vanilla crafting/smelting/stonecutting recipes from the game data), cost-based `Planner` (craft / smelt / cut / mine / hunt / procedure), `Gatherable`, `Stations` (shared table/furnace/stonecutter rules, base stations, pickup), `Workshop` (crafter + multi-furnace smelter + `Cutter` + procedures) |
| `loot` | `LootBook`: block, mob, chest and archaeology loot tables from the game data (chances, shears/silk-touch conditions); `LootSites`: where each structure is and how to recognise it |
| `hunt` | `MobHints`: where each mob lives (dimension, biomes, tell-tale structure blocks) |
| `procedure` | non-recipe items: `BeeNestProcedure` (honey bottles, honeycomb), `AnimalProcedure` (milk, wool, scutes, eggs), `BlockWorkProcedure` (stripped logs, carved pumpkins, mud, concrete), `OxidizeProcedure` (copper garden), `TrialChamberProcedure` (trial keys, ominous bottles, ominous keys, heavy core), `ChestLootProcedure` + `SculkGuard` (structure loot, quiet ancient cities); `StructureSearch` (finding a structure: in sight, explorer maps, exploring — shared with go-to tasks) |
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

### Combat
- **Fight, pillar or flee** is a damage race plus the ThreatNet's say: ticks to kill everything in reach with the
  best weapon (its own swing delay, not whatever is in hand) against the damage taken meanwhile — each melee mob's
  hit as the server deals it (base attack + weapon, by difficulty; the client never gets mob attack damage), cut by
  armor the vanilla way (big hits mostly go through), dodged by spacing (less against faster mobs) or blocked by
  the shield (not against axes: a piglin brute's disables it). The target counts however far it is (fighting it
  means walking up to it: left out past 8 blocks, a brute made the fight winnable at 8.1 and lost at 7.9, and the bot
  turned back and forth). Low health is two of the hardest hit in reach from death, not a fixed 8 (a brute's axe
  takes 9 through iron). Low health with one or two melee mobs at its side and a shield: fight behind the shield;
  a crowd: run. A change of plan must be wanted 15 ticks in a row (every tick of wanting something else counts).
- **Too strong to fight**: a mob it runs from that would beat it even at full health is remembered for ten minutes
  (`Bot.dreaded`): structure loot skips containers within 16 blocks of it and leaves a structure whose remaining
  ones are all guarded for later (a brute guarding a bastion chest won six trips back to it in ten minutes).
- **Pillar** refuge against melee mobs that can't climb: two blocks up, never beside ground a mob could step onto,
  not started with a melee mob within 4 blocks; up there it waits for them, eating when nothing can reach it — for
  20 seconds with none in reach, then it comes down and gets away (a brute keeps to its post: the bot once waited
  twenty minutes on a pillar eleven blocks from one).
- **Shield**: raised toward a shooter about to fire (drawn bow, loaded crossbow, charging ghast, lit blaze) and
  kept up a second after. One it can hit is chased while it reloads (a crossbow piglin backs off from anything
  within 5 blocks; behind a raised shield the bot never caught up) and struck, the shield going back up when it
  aims again. With a melee mob at its side the shield is combat's: it covers one side only.
- **Fight moves**: where a fight looks like the simulator's arena (target within 10 blocks, level, in sight, only
  mobs the simulator models — no piglin brutes or wither skeletons — and no drop into lava or of more than 8 blocks
  within 2 blocks: a hoglin threw the bot off a ledge into the lava sea mid-jump) the trained policy picks movement,
  sprint, jump, swing and shield every tick; elsewhere the hand-written rules (approach, strike when charged, shield
  between swings, step back inside 2.5 blocks). Each change of plan is written to the debug log with what it rested
  on (`combat <tick>: FIGHT/FLEE/PILLAR …`).

### Combat simulator (`tools/combatsim`)
- `combatsim.py` runs tens of thousands of fights at once on the GPU (PyTorch; each tick captured as a CUDA graph,
  ~14k fights/s on a GTX 1080 Ti): movement, attack reach and timing, hurt timers, knockback, shields, arrows,
  creeper fuses and spider leaps, numbers from the wiki and the game code. Zombies, skeletons, creepers, spiders;
  the hard half of training adds hoglins, piglins and crossbow piglins in crowds, with low health and worse gear.
  Every mob is seen as its class (melee, archer, creeper, spider) plus its health — exactly as `CombatObs` sees it
  in game, so a hoglin is a melee mob at twice a zombie's health. An archer's "drawing" is the shot that's coming:
  a bow being drawn, a crossbow loaded (not one being reloaded, which is the opening).
- Training: behaviour cloning of the hand-written rules first (from random weights it learned to hide from
  skeletons and never killed one), then evolution strategies — 512 perturbations × 64 shared fights a generation —
  on a score of survival, kills, damage dealt, health kept and speed.
- `train --init bc --mix 0.5` writes `assets/autocraft/combat_policy.json` whenever a new best beats the rules on
  the validation fights; a dev client picks it up within 30 s. Last export (generation 4190, half hard fights):
  won 92%, died 8%, against the rules' 71% and 29%.
- `viewer.html` (open it in a browser) replays fixed fights for the rules and recent generations side by side,
  refreshing as training records more; `combatsim.py viz` rebuilds it from saved snapshots. Python environment:
  `tools/combatsim/.venv` (torch with CUDA).

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
  - trial chambers: tunnel at Y −30 to find one, beat trial spawners from where they can see the bot (vanilla's
    check: a clear line from the eyes to the spawner; their mobs become hunt targets), collect the keys, open
    vaults (ominous bottles come from normal vaults). A vault lights up only for a player at its side who hasn't
    opened it, so it's judged there, not from across the chamber (read from afar, every vault looked opened and
    the bot left after one spawner, never getting a bottle). For ominous keys and the heavy core: drink an ominous
    bottle, get in sight of a normal spawner (that turns Bad Omen into Trial Omen), then beat the spawners — a
    normal one turns ominous the moment it sees the bot under Trial Omen, even cooling down, so the ones already
    beaten for keys are fresh trials — and open ominous vaults. Opened vaults are remembered in
    `config/autocraft/trial_vaults.json` (the older `trial_chambers.json` counted vaults never opened, and is
    dropped); a spawner or vault out of reach is skipped, not the chamber; a chamber with nothing left is left for
    the spawners' 30-minute cooldown (every part of it, its far end included), or until a bottle or an omen makes
    its ominous side worth it.
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
an explorer map in the inventory pointing at one is followed first). Its tell-tale blocks are looked for across
everything loaded, the whole render distance — one in view is walked to, not tunnelled past (a 4-chunk look missed
a bastion 100 blocks off); finished ones are left out in the scan itself (filtered afterwards, an unfinished wing's
one tell block came and went and the bot swung between heading there and exploring away every second). A
structure is the chunks holding its tell blocks flooded out from the one found (a bastion is wider than 48 blocks):
its containers are looked for over all of them, and finishing it marks all of them done. It opens the containers
one by one and takes the item. Opened containers and finished structures are remembered per world
(`config/autocraft/structure_loot.json`); chests at your bases are never touched. Structures in the Nether trigger
a portal trip (with the Nether kit).

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
  carries on; burnt items (lava, fire) are written off, and so is a death that left nothing worth the walk: worth it
  are job loot, diamond gear and up, enchanted or rare things, or iron worth 8 ingots or more in armor, tools,
  buckets and iron (full iron armor and tools were once written off, and an hour went into remaking them). Off: the
  job stops. The five minutes dropped items last count only while their chunk is loaded — every tick, whatever the
  bot is busy with (counted only while walking back, a death spiral of fights and new deaths had the bot still
  heading for items ten minutes gone), and saved with the task. Below 14 health the trip waits to heal first: what
  killed it is usually still there.
- Trips that dig (back to the items, a go-to task, fetching from a base, the trip to the base a task ends at): the
  digging on the planned path is timed with the tools carried and with a wooden or stone pickaxe made from what's
  carried; when the saving beats the making by 10 seconds, the pickaxe is made first. A trip that finds no way at
  all with what's carried (by hand, stone prices every route through it out of the search) makes the pickaxe and
  plans again before giving up.
- *Log off near players*: another player (not a teammate) within the radius ends the session.
- The task in progress (job with deposited counts and skipped items, build order, go-to destination, the base it
  ends at and its store choice, whether it's already on the way there, recovery) is saved per world in
  `config/autocraft/tasks.json` every few seconds and resumed a few seconds after joining again (older files load
  as before: the new fields are just missing).
- Bases: as many as you like per world (`config/autocraft/bases.json`), e.g. home, a nether base, a mining
  outpost. Inventory-full trips and base furnaces/tables use the nearest base in the current dimension (home
  when there's none); where a task ends is its own choice (below).
- Chest memory: every chest or barrel screen that opens — the bot's or yours — is read and remembered per world
  (`config/autocraft/chests.json`). Prerequisites a base's chests hold are fetched from the quickest such base
  when the round trip beats gathering, builds take from the base nearest the site that has the item; *Materials
  at base* (or `!ac base stocked on`) checks base chests not looked into yet.
- "Per world" means the singleplayer save folder or the server address, plus the world's hashed seed (which the
  server sends every client): two singleplayer worlds both called "New World", or two worlds behind one address,
  never share bases, task tree, structures, chests, deaths, copper garden, used vaults, spawn point and respawn beds or
  resume state. A task-tree run belongs to the world it was started in and waits while you're elsewhere. (Files from
  before this keying start over.)

### Go-to tasks
A task kind of its own (*+ Go to* on the Tasks tab, `!ac goto`), run like any job: shown as the running task,
on the overlay and the Bot tab, saved and resumed, stoppable, and the whole survival stack (eating, combat, gear,
food, storage, death recovery) keeps going — the walking behavior is last in the chain. Targets:
- **Coordinates**: x z with an optional y (none: any height on that column it can stand at), in the dimension it
  is in when the task starts, or the Overworld/Nether picked.
- **A base**: its position and dimension when the task starts.
- **A structure**: any kind in `LootSites` but buried treasure (village, desert pyramid, jungle temple, pillager
  outpost, mansion, abandoned camp, shipwreck, ruined portal, desert well, trail/ocean ruins, dungeon,
  mineshaft, stronghold, trial chamber, ancient city; nether fortress and bastion in the Nether). The nearest one
  whose tell blocks are anywhere in loaded chunks (or a tell mob in sight, or an explorer map's marker) is walked
  to; none seen, it explores the way structure loot does (`StructureSearch`: surface legs toward the structure's
  biomes, or tunnelling at its depth) for up to 60 legs, then says "Can't find a …" and the task stops.
Another dimension is reached through a portal (`PortalBehavior`, as for base trips and death recovery); far
targets are flown to with an elytra when that pays. Arriving says "Arrived at …" and the end-of-job flow follows.

### Ending at a base
Every task — collect, fetch, build, farm, go-to, and groups (once their subtasks are done) — can end at a base:
*Ends at* on the task (or `then <base> [go|emptiest|similar]` on the command; default: none, the task ends where it
is). One end-of-job flow (`FinishBehavior`) serves all of them: once the task's goal is met, the stations put
down are picked up; with an ending the bot goes to that base (through a portal when it's in the other dimension)
and then, by the second choice:
- **just go there**;
- **emptiest chest**: the base container with the most free slots, filled before the next;
- **similar items**: per stack, the base chest already holding the most of that item, else the one holding the
  most of its kind, else the emptiest; a full chest passes the stack to the next choice, so different materials
  end up in different chests. Kinds, checked in this order: *wood* (logs, wood, stems, planks, saplings,
  leaves, sticks), *ore* (ores, raw metals, ingots, nuggets, gems, coal, redstone, lapis, quartz, debris and
  scrap, metal/gem blocks), *drop* (wool, bones, string, rotten flesh, gunpowder, pearls, blaze rods, slime,
  leather, feathers...), *farm* (food, seeds, crops, eggs), *stone* (cobblestone, stone, deepslate, andesite,
  diorite, granite, tuff, basalt, blackstone, netherrack, end stone, sandstone, obsidian and their bricks/slabs),
  *earth* (dirt, grass, mud, clay, sand, gravel, flint, soul sand/soil, snow, ice); anything else only matches
  itself.
Base containers are the chests and barrels within 8 blocks of the base; what they hold comes from chest memory,
and any not looked into yet are opened first so the choice knows them all. Stored: what the task was for, filled
shulker boxes and bundles, and everything else carried except the bot's kit — gear (anything with durability:
worn armor stays on, tools, weapons, shield), food, empty containers (buckets, boats, bundles, shulkers), working
supplies (sticks, coal, stations, torches, pearls, arrows, rockets, milk, totems) and the 128 building blocks it
pillars with. Then it stops and logs off as set. (Before, a finished job always hauled its loot to the nearest
base; now that's this choice.)

### Respawn beds (setting, off by default)
Far from the spawn point a death costs a long walk back to the dropped things (or the items, when they despawn
first). With *Respawn beds* on (`!ac spawn beds on`, Settings tab) the bot puts a bed down near its work and clicks
it — in Java Edition that sets the respawn point before the daytime and monster checks, so it works at noon and with
mobs around; at night with none around it falls asleep and gets straight up again (sleeping isn't the point).
- **Spawn point tracking** (`SpawnPoint`, per world in `config/autocraft/spawn_points.json`: the point with its
  dimension and kind — bed, world spawn, set by you — plus the beds the bot put down). The client is never told
  its respawn point, so: *Set spawn point here* / `!ac spawn here|x y z`; the game's "Respawn point set"
  (`block.minecraft.set_spawn`) records the bed the bot just clicked, or the bed/anchor under your crosshair; after
  every death the place the player came back (once settled, 1 s after the respawn) is checked against it —
  within 16 blocks confirms it, elsewhere replaces it ("You have no home bed..." means the bed is gone or blocked:
  world spawn, and the bed is dropped from ours). Nothing recorded: the world spawn the client was sent.
- **When**: only in the Overworld (beds explode in the Nether and End), only with the spawn point more than the
  set distance away (default 256 blocks, horizontally, or in another dimension), and only when one of these says
  a death is likely:
  - it already died during this job (or build);
  - a Nether trip is about to start: the portal behavior is walking to a portal within 24 blocks — a Nether death
    respawns in the Overworld, so the bed goes down by that portal before the bot goes through;
  - the job's next item comes from a guarded structure (trial chamber, ancient city, stronghold, woodland mansion,
    pillager outpost, dungeon) and its tell blocks are within 2 chunks or its guards within 48 blocks;
  - the work hunts hostile mobs (creepers for gunpowder, trial spawner mobs);
  - night, at least 2 hostile mobs within 24 blocks and armor below 10 points (less than iron chestplate + leggings).
  With the spawn point already near the work nothing happens; at most one new bed every 10 minutes (exploring work
  would otherwise leave a trail of them).
- **How**: one of our beds within 64 blocks of the work is clicked again; else a carried bed goes down; else the
  planner gets one (`Planner.bed()`: the cheapest color — three wool of one color and three planks, so usually
  shorn or hunted white sheep), its raw materials posted as the `BED` need (after gear and food) that the collector
  gathers; the maker waits for it like for food. Before work at a guarded structure a bed is got ahead of time,
  while still up where the sheep are. The bed goes on flat, solid, dry ground within 8 blocks of the work (both
  halves free with room above — vanilla won't use an "obstructed" bed — no water or lava touching, no hostile mob
  within 6 blocks, outside our builds), placed from behind its foot so it lies the way chosen. Then it's clicked
  (not sneaking) and the game's answer awaited: "Respawn point set" (or, if it already was this bed, the daytime /
  monsters refusal that follows the setting); "too far away" walks closer, "obstructed"/"occupied" or no answer
  after 3 clicks gives the bed up (an old one of ours: a new bed instead). A chat line says where the spawn point
  moved; the status line shows each step ("respawn bed: putting it down at ...").
- **Failure**: getting a bed may take 5 minutes, placing tries 4 sites; then a warning ("Respawn bed: ...; trying
  again in 5 minutes"), 15 minutes when nothing can make a bed. Quiet mode (an ancient city hears every block
  placed) never puts one down. With the setting off nothing of this runs; spawn tracking still keeps the point
  (changes only go to the log).

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
| 6 | **Storage** — junk thrown out of reach (never job loot; surplus stone before anything unknown), bundles, shulker boxes, base chests with deposited counts | code complete |
| 7 | **Nether** — kit (gold boots, flint and steel, iron pickaxe, bucket, blocks, extra food); obsidian mined or cast; portals built/reused and remembered; trips home for materials or base storage; nether mob rules | code complete |
| 8 | **Travel** — boats over water/ice; elytra flights with rocket crafting and durability checks | code complete |
| 9 | **Biomes & mending** — biome-directed exploration; mending gear repaired in the offhand | code complete |
| 10 | **Schematics & farms** — user schematics, built-in starter base and farms, farm operation and auto-build | code complete |
| 11 | **Crafted jobs & UI** — any craftable/smeltable item as a job target (unobtainable ones skipped); control panel (K) and status overlay | code complete |
| 12 | **Litematica-style placement** — ghost preview in the world, keyboard/mouse positioning, rotate + mirror, build progress overlay | code complete |
| 13 | **Materials & team** — substitution (wood swaps, stand-ins, filler, replacements); team mode over TCP with build roles and split jobs | code complete |
| 14 | **Fixes** — shared station rules, multi-furnace smelting, station pickup, faster pathing, quick-kill combat with crits, drowning reflex, tool tiers scaled to work | code complete |
| 15 | **Mob drops & procedures** — loot tables, hunting, honey/wool/milk/eggs, block work, copper garden, trial chambers | code complete |
| 16 | **Additions** — smooth aim, mob farm, stonecutter, portal time costs + bucket portal, death recovery, player alert, big-build stash and tool investment, chest memory and base fetches, resume after relog, task tree, respawn beds + spawn point tracking, go-to tasks (coordinates, bases, nearest structure), ending any task at a base (just go / emptiest chest / similar items) as one end-of-job flow | code complete |
| 17 | **Hardening** — in-game testing; fixed so far: stalls (gear planning was blocked by any mob within 8 blocks, worn cheap tools refused, job loot never used for tools, an iron pickaxe chosen to mine the iron for itself when a little raw iron was carried, the collector waiting for a pickaxe the gear planner never planned: it now asks for one for exactly what it's stuck on and gets an answer the next tick), far targets past chunk borders, sunken logs chosen over dry ones, hurt flee/work ping-pong, eating blocked by mobs behind walls, searching for crafted blocks (iron block for an iron pickaxe), saves leaking between same-named worlds, slow/re-planning swimming (now sprint-swims skimming the surface); boats over water, shipwreck wood; `!ac why` decision trace; climbing straight up into a lake bed (the straight climb now needs a dry column), eating while the shield was raised, moves the executor kept failing re-planned again (now avoided for 2 minutes), fire vetoing the step that put it out, sneaking onto magma a tick late (landing on it and stepping onto it), drops stalled 0.3 off the column's middle (still resting on the next block), a failed pillar falling back to the very fight it had judged unwinnable, mob hits misjudged (the weapon in hand, axes through shields, armor against big hits, the swing delay of whatever was in hand); job items a procedure also makes are planned (mud from dirt and a water bottle instead of a trip to find a mangrove swamp), gathering what only grows in biomes none of which is around costs a trip, crafts walking at an unreachable table (ours goes down instead) and the crafting backoff undone the next line, left sitting in a boat (paths get out first; the ridden boat counts as carried), bottles "filled" at water behind rock (only water in view now); netherite pickaxe end to end (bastion template, smithing table, smithing); copper only structures hold (exposed, weathered, oxidized) priced as structure loot and the copper garden's spot relaxed, a spent trial chamber re-entered every tick while its spawners cooled down, a trial spawner waited at through a wall (its line of sight was cast from the wrong end), junk dropped at its feet and picked straight back up two seconds later (thrown out of reach now, surplus stone before unknown items like breeze rods), a crossbow being reloaded read as about to fire (the shield stayed up and the trained fight held back just when it should close in), the shield held toward one shooter while a melee mob hit its open side; bastions: a brute guarding a chest fought or fled six times over (now remembered and avoided), fight and flight swapping every second at the edge of the damage race, the trained moves used on brutes and beside the lava sea, the search swinging between a bastion and exploring, "done with this bastion" eleven times a second (a cached scan), twenty minutes on a pillar waiting for a brute that keeps to its post; heavy cores and sniffer eggs searched for as world blocks (they're loot only), a stop typed in the seconds after joining undone by the saved task resuming, every base chest skipped as "not a storage menu" (the player's own inventory counted as an opened container), item-picker clicks swapped (26.3's SDL mouse buttons: left 1, right 3), a running procedure tunnelling on by hand while gear waited for the wood for a pickaxe, full iron gear left behind after a death; blazes: two at arm's length burned the bot to death behind its shield (a shooter in sword reach is now fought, not blocked), a crash when the only blaze in reach drifted off mid-swing, heading for an unreachable bit of fortress and exploring swapping every second, netherrack thrown out a part stack at a time while six full stacks stayed (a kind in several stacks now loses a full one); trial chambers: every vault judged opened from across the chamber (they light up only for a player at their side) so the bot left after one spawner and never got an ominous bottle, the chamber's other spawners abandoned when one was out of reach, rewards walked away from before the vault had ejected them, the ominous path skipping spawners beaten before (they turn ominous anyway); guarded work (trial chambers, bastions) kits up with iron armor and an iron sword first and tops health up in every lull; searches from underground (powder snow) explored the caves for an hour instead of climbing out; dropped items' despawn clock restarted by every death and paused during fights | in progress |
| 18 | **No-x-ray mode** (setting) — the bot only knows blocks it has actually seen: ores, structures and chests are found by strip-mining at the right Y level, following caves and exploring, never by scanning through stone (for servers with anti-xray, and play that looks fair) | planned |
| 19 | **Combat simulator** — GPU fight simulator, behaviour cloning + evolution strategies, trained fight moves in game with live reload, replay viewer; combat decisions on real mob damage | code complete, in use |

"Code complete" = compiles against the real 26.3 client jar; not yet run in-game (see Build).

## Build & test
- `./gradlew build` → `build/libs/autocraft-<version>.jar` (drop into `.minecraft/mods` with Fabric API).
- `./gradlew runClient` starts a dev client; press K, or try `!ac goto ~100 ~-50`, `!ac goto structure village`,
  `!ac goto base home`, `!ac build starter_base here`, `!ac collect log 16 then home similar`,
  `!ac collect iron 8`, `!ac collect torch 32`, `!ac collect ender_pearl 4`,
  `!ac collect honeycomb 6`, `!ac farm cobblestone then home emptiest`; respawn beds: `!ac spawn beds on 64`, `!ac spawn`, then
  `/time set night` with zombies around in leather, or a job 100 blocks out after a death.
- `./gradlew runHarness` / `runHarness2` start dev clients in `run/` / `run2/` driven by lines appended to their
  `harness-in.txt` (`DevHarness`: server commands, `!ac` commands, state dumps every 5 s; `widgets` / `click
  <label>` list and press the open screen's buttons; `respawn`; `record <name>` / `record stop` saves what the game
  shows as an MP4 in `recordings/` with ffmpeg, `camera back` for a third-person view; `biomemap [radius] [step]`
  logs the surface biomes around the player from the world generator, as a character map).
- Loom must download Minecraft's libraries from `libraries.minecraft.net`; build on a network that allows it.

## Known limits
- Servers with anti-xray or strict anti-cheat will fight the bot (scanning sees hidden ores; reach and timing are
  vanilla but not humanized beyond the aim curves) — until the no-x-ray mode (phase 18) exists.
- End travel, enchanting and villager trading are out of scope.
- Chest-only loot from End cities isn't reachable (no End travel). The mob farm gives no player-kill-only drops
  (spider eyes) and spawns slowly when caves nearby compete for mob spawns.
- Lava escape needs solid ground within ~4 blocks (or the last safe spot) or a fire resistance potion; a fall
  into a wide lava lake with no water bucket, no blocks and no solid edge in reach can still be fatal.
- Builder limits: block entity contents (chest items, sign text) aren't recreated; waterlogging isn't
  placed.
- Without a pickaxe deep underground the collector goes for the nearest wood it sees (a mineshaft's supports) even
  when reaching it means punching through deepslate (15 s a block) rather than walking back up its own tunnel to the
  trees; it gets there, slowly.
- Piglin brutes are avoided, not beaten, in iron gear; a bastion whose remaining chests a brute guards is left for
  another one (tried again after ten minutes).
- After a death the respawn point is recorded as the world spawn only when the respawn lands within 16 blocks of the
  world spawn the client knows; farther (a big spawn radius) it's labelled a bed.
- Trial chambers are still often deadly: skeleton and husk waves took the mace bot from full health and full iron to
  dead within a minute or two of starting a trial; it then recovers and tries again. The Bad Omen → Trial Omen →
  ominous spawner path is written to vanilla's rules but not seen in game yet (no ominous bottle has dropped from
  the vaults opened so far). Choosing between two vaults at an equal distance, it can switch target a few times
  before settling.
- Searches that start underground (powder snow, bee nests, animals, surface structures) climb to the surface first
  now; not yet seen in game on the powder snow job that first showed the problem.
