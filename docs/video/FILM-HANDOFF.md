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
  frame, `Cam.cut` for hard cuts. `Film.clearView` pushes the camera in front of solid blocks within 6 blocks
  ahead (and out of blocks); `Shot.clearCameraPath()` removes leaves/logs/plants (never blocks the sets placed,
  tracked in `Stage.BUILT`) along the camera's path before each shot — for filming in forests.
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

## Next steps
1. Have the user run `!ac film record` (or single shots: `!ac film record nether`) and send the video or the
   chat errors ("Film stopped:", "Film graphics failed:", "Recording stopped:"). Fix what breaks.
2. Things most likely to need tuning in game, untested so far:
   - LootShot city survey: route quality, chests with unreachable wool spots, darkness (light block levels),
     the time `findNearestMapStructure` + chunk generation takes (the client freezes while sets build).
   - TeamShot with the user's schematic (`film*.litematic` in `config/autocraft/schematics/`; they downloaded
     `film.litematic`): placer spots, camera drop-ins framing, gatherer timing at the chest.
   - NetherShot geometry/camera; the fall reads fast (15 blocks in one bar).
   - Camera collision feel (`Film.clearView`: snap in, ease out at 0.6/s) and how much foliage
     `clearCameraPath` removes in a forest.
   - Shot pacing against the music now that shots are much shorter (Plan and Find are 8 bars each).
3. Update `docs/video/storyboard.md` ("In game" section) to the new cut list and the music, once it plays right.

## Checking
Gradle can't build here (`libraries.minecraft.net` is blocked). Compile with
`python "$USERPROFILE/.autocraft-dev/check.py"` (javac against the game jar, Fabric libs and dev-only stubs in
`~/.autocraft-dev/stubs-out` — keep those). Before calling work done: compile a fresh clone from GitHub the same
way. `~/.autocraft-dev/uiprev/` has a Python mirror of the Pick shot (`mc.py`, `pick.py`, `clip.py`, `light.py`)
that renders previews with the game's textures; it mirrors `PickShot` and the status light only, and its timings
predate the music cut.
