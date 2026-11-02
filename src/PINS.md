# What moves the golden pins

The golden pins in `test/xaos/test/Golden.java` hold state hashes for fixed
seeds, sites and tick counts. An edit in the first three lists below can
change world generation or the simulation, and so move the pins. When that
is what you meant, record the new values in the same commit and say why.
Edits in the last list never move them.

See `src/README.md` for the package-by-package map, and its testing section
for how the pins and the headless runs work.

## Simulation code

Whole packages under `src/xaos/` (every file):

- `main/` (`World.java`, `Game.java`)
- `datacenter/` (the data center rules)
- `tiles/` (`Cell.java`, `Tile.java`, `terrain/**` including fluid `Water`/`Lava`, `entities/**`: livings, items, buildings, special)
- `tasks/`, `actions/`, `generator/`, `stockpiles/`, `zones/`
- `caravans/`, `dungeons/`, `effects/`, `events/`, `gods/`, `skills/`
- `campaign/` (mission selection drives world generation)
- `data/` (the saved and hashed data classes)

Root files: `Towns.java`, `TownsProperties.java`.

This part of `src/xaos/utils/` (the rest of the package is in the last list):

`Utils`, `UtilsDice`, `UtilsGeometry`, `UtilsLineOfSight`, `UtilsSavegame`, `UtilsString`, `UtilsXML`, `UtilsIniHeaders`, `UtilsFiles`, `Names` (draws random numbers for names), `Date`, `Point3D`, `Point3DShort`, `AStarQueue`, `AStarBinaryHeap`, `AStarNodo`, `AStarQueueItem`.

## Data (not Java, but it defines behavior)

The pins hash the result of loading these, so they matter as much as the code:

- `src/data/**`: all game XML (`items`, `buildings`, `livingentities`, `terrain`, `actions`, `effects`, `events`, `caravans`, `skills`, `zones`, `types`, `prices`, `priorities`, `names`), every `gen_*.xml`, `campaigns.xml`, and `data/languages/*.properties`.
- The game's pictures, sounds and font (`data/graphics/`, `data/audio/`, `data/fonts/`) are not part of it: headless runs never read them (textures come back as stubs and audio is off).
- `src/graphics.ini`: in part. A tile's `animationFrameDelay` sets the range of the random number the `Tile` constructor draws, so changing frame delays shifts the random stream. Atlas coordinates only affect drawing.
- `src/towns.ini`: the values that select folders, mods and the campaign feed the simulation; window, frame rate and key values do not.

## Watch these

- Every `new Tile(...)` draws one random number. A picture loaded before or during world generation (a new terrain kind, a new zone, a menu icon built early) moves every seeded map.
- Panel classes with `static Tile ... = new Tile(...)` field initializers: what the panel does is free to change, but each such field draws one random number when the class first loads, so the lists of those fields are not.
  - `UIPanel.java` and `MiniMapPanel.java` load in the middle of the pinned headless runs; the golden pins catch edits here.
  - `MainPanel.java` loads during the scenario tests; the scenario pins catch it.
  - `TradePanel.java` and `menus/SmartMenu.java` never load in headless runs, so no pin guards them, but an edit to their Tile field lists still shifts the windowed game's random stream. Leave those lists alone.
  - The split-out `*UIPanel` classes and `MainMenuPanel`/`MessagesPanel` (which also load during pinned runs) draw no random numbers when they load: their `static Tile` fields are declared but not built then. Keep it that way; do not move a `new Tile(...)` into any of their field initializers or static blocks.
- `src/xaos/property/**`: the code that parses the `.ini` files. You will rarely touch it, but it feeds simulation inputs.
- `src/xaos/TownsHeadless.java`: the harness can grow, but its two hash methods define the pins; changing them changes every pin.

## Never moves the pins

- `src/xaos/compat/**` (the platform layer)
- `src/xaos/panels/**` behavior (the interface, minimap, menus, info), apart from the Tile field lists above
- `src/xaos/setup/**` (the missing-files check; not in the headless path)
- `src/xaos/launcher/**` (the settings window shown before the game; not in the headless path)
- The rest of `src/xaos/utils/`: `UtilsGL`, `UtilsAL`, `UtilsKeyboard`, `UtilsServer`, `Log`, `Messages`, `LanguageData`, `LocalResourceClassLoader`, `PropertiesWriter`, `UtilFont`, `CharDef`, `ImageData`, `TextureData`, `ColorGL`
  - One exception in `UtilsServer`: downloading a buried town draws one `Utils.getRandomBetween` to pick a file.
- `test/**` (the tests themselves)
- `src/audio.ini`, and atlas coordinates in `graphics.ini`
