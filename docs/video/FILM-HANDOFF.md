# In-game film — handoff notes (temporary)

State of the `!ac film` director (`src/main/java/com/autocraft/cinema/`) at the hand-off, and what's left. Delete this
file once the film is finished.

## Where things stand
Everything compiles against 26.3 (see "Checking" below). **The latest changes have not been run in game.** The
user tests in game and reports back; nothing here can run Minecraft.

Done in the last round (untested in game):
- **Cut to the song** (ParagonX9 — *Chaoz Fantazy*, Newgrounds, CC BY-SA, 162.34 BPM, 2:12). `Score` holds the
  tempo, the bar → second mapping and the cut list `Score.CUTS`; each shot has a `slot` (its place in the film) and
  `bar(globalBar)` gives the shot-local time of a bar. Shots: Open 0 (bars 0–18, logo lands on the drop at bar 16),
  Pick 1 (18–32), Plan 2 (32–40), Find 3 (40–48), Survive 4 (48–51), **Nether 5 (51–57, new)**, Loot 6 (57–68,
  breakdown; wool at bar 64), Team 7 (68–81, time-lapse from bar 73), End 8 (81→136 s, logoff on bar 88).
  `Film` runs one clock for the whole film (`ticks`/`shotStart`) so cuts never drift off the music.
- **Story clocks**: `Shot.clock(t)` (what `tick` and `at`/`every` see) and `Shot.Warp` (bars → story seconds) let
  a shot keep its old internal timing but land its moments on bars (Plan, Find use this; Team uses a warp for
  slow-motion drop-ins).
- **Recorder** (`!ac film record [60fps] [shots]`): frame-exact clock; pipes frames to ffmpeg; then muxes
  `<game dir>/autocraft-film/music.mp3` (or .ogg/.wav/.m4a/.flac) from the right song offset if the recorded
  shots are one unbroken stretch of the film. The user's copy of the song is
  `85046_newgrounds_paragonX9_Chaoz_Fantasy.mp3` in the repo root — **do not commit it**; it goes in the game's
  `autocraft-film/` folder as `music.mp3`.
- **Camera**: keys can ride on actors (`Shot.on(actor, dx, dy, dz)` suppliers), `Cam.watch` keeps a subject in
  frame, `Cam.cut` for hard cuts. Between the scripted move and the screen sits `Rig`: it steadies the move a little
  (0.1 s), and when the scripted spot is inside a block or something stands between it and the look point it picks
  the smallest nearby offset that sees clearly (up, in toward the subject, sideways, mixes; current offset kept
  while it still works) and eases there and back (two 0.22 s stages) — it may pass through a block while easing
  rather than jump. Nothing in the world is cleared any more (the old foliage clearing and the opening's trunk
  cutting are gone).
- **Puppets don't go through blocks** (`Actor.settle`): on their feet they stand on the floor under the scripted spot
  (a step up to 1.1, down to 1.3; water holds them where scripted), are pushed up out of blocks, and wait at a wall
  (after 20 ticks they go through anyway to stay with the script). `Actor.free` (jumps, falls, the placer perched on
  its walls, the pillar) only keeps them out of blocks; `Actor.teleport` skips everything in between (Plan's hops).
  `Walker` plans walks on real ground (A* over standable cells, straightened); the opening's walk goes round the
  trees with it, and LootShot uses its cell rules (with sculk to avoid).
- **Crash-proofing**: every hook the film has in the game (camera mixin, HUD, level drawing, puppet ticks, the
  recorder's timer and frame hooks) catches what it throws: `Film.fail` puts "Film stopped (where): error at
  File.java:line" in chat, logs the full trace, and stops the film at the next tick. The user reported a crash
  in the opening of the previous build; no log was available here, so the cause is unknown — the next run should
  show it as one of those chat lines instead of a crash (if it still crashes, it's outside our hooks: ask for the
  crash report from `.minecraft/crash-reports/`).
- **Travel**: sets further than `Film.FAR` (120) from the hub are teleported to between shots (black loading
  screen; the recorder doesn't capture it). Team (+40, +220) and End (−220, +40) are now far from the other sets;
  Loot goes to the nearest real ancient city.
- **Loot** (`LootShot`): finds the nearest real ancient city (`findNearestMapStructure`, then scans ±64 blocks around
  its centre for chests, sensors, shriekers), picks a chest a sensor would hear, finds where wool goes with the
  mod's own `procedure/SculkGuard.woolSpots` (made public), plans a sneaking route by BFS that never steps on sculk,
  and adds invisible light blocks along it. Falls back to a built hall if the world has no city.
- **Team** (`TeamShot`): the placer stands where it could really reach each block (`planStands`: ground or on top of
  blocks already placed, within 4.5 blocks, not inside the block going in); four slow-motion drop-ins (digger,
  gatherer chopping, gatherer at the chest, placer) on bars 73.75 / 75.25 / 76.75 / 78.25.
- **Survive** cut to 3 bars (creeper crit on the beat, arrow, sprint and kill). **Nether** (new): drop from a
  ledge, water bucket clutch on bar 52, one-bucket portal cast a block a beat, light, walk in, purple, step out in
  a netherrack pocket (fake nether, built underground beside the cave).
- End card: "Press K in game", `modrinth.com/mod/autoCraft_`, and `Score.CREDIT` (music credit — required by
  the licence; also put it in the video description).
- Logos are raised above whatever is under them (`Logo.above`); the End logo used to clip into the ground.
- Status light: small bobbing/turning pixel cube with a bloom halo (`Fx.pixelCube`, `Shot.lights`).

## Done from the last list (this round, still untested in game)
- **Crash at the opening logo: "Missing elements in vertex"** (the user's report). Hand-written vertices only set
  the elements of the vanilla format (block: position, colour, UV0, light). A shader pack (Iris) widens the block
  format with a normal and more, so the first block geometry the film draws, the logo flying in, left elements
  unwritten. `BufferBuilder` only checks when the *next* vertex starts or the buffer is built, in vanilla code, so
  it can't be caught where it's written. Every hand-written vertex now goes through the all-elements
  `addVertex(x, y, z, colour, u, v, overlay, light, nx, ny, nz)` (`Fx.vertex`, and `GhostRenderer`, the mod's own
  schematic overlay, which had the same bug); elements a format lacks are skipped. [Inferred from the message and
  the vanilla code: not reproduced here.] Deferred draw callbacks also report errors through `Film.fail` now, the
  logo's fly-in is one batch, and `latest.log` has a trail ("Film: starting …", "Film: <shot> at <t> s").
- **The user's `film.litematic` never loaded**: it's plain (uncompressed) NBT, and the loader only read gzip, so the
  film fell back to the cottage. `SchematicLoader` now reads both. It is not the modern house: 51×42×57, 25,922
  blocks, 501 block states (spruce, bamboo, beehives, its own dirt and grass base). Checked offline with a Python
  copy of `TeamShot.planStands` on the real file: every block has a spot within reach (0 misses), ~19,800 moves
  over the build. The time-lapse places ~75 blocks a tick at full speed — heavy live, fine when recording.
- **Sets build in the background** (`Film.building`): the window stays alive with "Building the sets…" while the
  server works (the ancient city search and chunk loading used to freeze the game), then it goes up over the hub.
- Team: the digger's first boulder now breaks inside its slow-motion drop-in (it used to still be walking).
- Survive: the sprint at the skeleton arrives as the blow lands (it used to swing 0.2 s early, short of it).
- `docs/video/storyboard.md` "In game" section: the cut list on the music, the song, recording with it.

## Next steps
1. Have the user run `!ac film record` (or single shots: `!ac film record open`) and send the video or the chat
   lines ("Film stopped (…):", "Recording stopped:"). If it still crashes, the end of `logs/latest.log` (the
   "Film:" trail and the stack trace) says where.
2. Things to judge on the user's video, then tune:
   - LootShot in the real city: route, wool spots, darkness (light block levels).
   - TeamShot with the 25,922-block build: frame rate of the ghost (all remaining blocks are drawn every frame;
     limit it to the next few layers if it's slow), the camera drop-ins framing, the gatherers at the chest.
   - NetherShot geometry/camera; the fall reads fast (15 blocks in under a bar).
   - Camera feel: `Rig` timings (`EASE`, `STEADY`), the candidate offsets, `SUBJECT` (how close to the look
     point a block counts as part of the subject); the forest opening is the hardest case.
   - Puppet collision: waiting at walls / going through after `PATIENCE`, the step and drop limits, shots where a
     script deliberately moves through blocks and needs `free` or `teleport`.
   - Shot pacing against the music (Plan and Find are 8 bars each).
3. The music licence (CC BY-SA) asks for the video to be shared alike, which game footage can't be; the user may
   want to ask the artist.

## Checking
Gradle can't build here (`libraries.minecraft.net` is blocked). Compile with
`python "$USERPROFILE/.autocraft-dev/check.py"` (javac against the game jar, Fabric libs and dev-only stubs in
`~/.autocraft-dev/stubs-out` — keep those). Before calling work done: compile a fresh clone from GitHub the same
way. `~/.autocraft-dev/uiprev/` has a Python mirror of the Pick shot (`mc.py`, `pick.py`, `clip.py`, `light.py`)
that renders previews with the game's textures; it mirrors `PickShot` and the status light only, and its timings
predate the music cut.
