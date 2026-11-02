# Working on the code

Parted Magic Server Room is made by Parted Magic LLC. The code is under the
GNU GPL v3. The README has the code and art licenses.

## Build and run

- `./gradlew run` (`gradlew.bat run` on Windows) builds the game and starts
  it. The game runs with `src/` as its working folder and reads `towns.ini`,
  `graphics.ini`, `audio.ini` and `data/` from there.
- `./gradlew runHeadless -Pseed=42 -Pticks=3000` runs world generation and
  the simulation with no window. `-Pmap=` picks the site: `normal`,
  `desert`, `jungle`, `mixed`, `snow` or `mountains`.
- `./gradlew jpackageZip` builds the release zip for the computer it runs
  on. It cannot build for another operating system.
- On a Mac, `-PmacSignIdentity="Developer ID Application: ..."` signs the
  app with that identity, and `-PmacNotaryProfile=<notarytool keychain
  profile>` also has Apple notarize it and staples the ticket. Both need
  the Developer ID key in an unlocked login keychain, so run them in a
  Terminal window on the Mac, not over ssh.

[src/README.md](src/README.md) maps the code package by package.

## Tests

`./gradlew test` runs the JUnit suite in `test/`. The game keeps its state
in static fields and supports one game per Java process, so the test task
starts a new process for each test class. Seeded runs repeat exactly; keep
new tests that way.

The golden pins in `test/xaos/test/Golden.java` hold state hashes for fixed
seeds, sites and tick counts. A change to world generation or the rules
changes them. When that is the point of the change, record the new values in
the same commit and say why. [src/PINS.md](src/PINS.md) lists the edits that
can move them.

## Game files

`src/data/graphics`, `src/data/audio` and `src/data/fonts` hold the game's
own pictures, sounds and font. Each folder has a `PLACEHOLDERS` file. Keep
it: the release build will not package a folder without one. That check
keeps Towns' own files out of the game. They are not free, and they must
never be added here.

The game's content is XML in `src/data/`: items, jobs, creatures, terrain,
events, menus, sites and the tutorial. Most of the text players read is in
`src/data/languages/messages.properties`; item names and the tutorial are in
the XML.

## File conventions

- Java files are UTF-8 without a BOM, with LF line endings.
- Keep `src/data/languages/*.properties` plain ASCII. Java reads them as
  UTF-8 and falls back to ISO-8859-1, so other characters can come out
  wrong.
- Match the code around your change. The Towns code is written in Java 6
  and 7 style (no lambdas, no diamond operator, raw types), and the
  `unchecked` compile warnings are expected. `xaos.compat` uses current
  Java, and `xaos.datacenter` mostly follows the older style.
- Saved games carry a version (`Game.SAVEGAME_VERSION`). A change to what
  gets saved needs a new version, and older saves must still load.
