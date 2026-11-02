# Parted Magic Server Room

A free game from Parted Magic LLC. You build and run a data center: install
server racks, give them power, cooling and network ports, and keep them
running for the customers who pay for them.

Drives wear out and servers break. Your technicians fix them with Parted
Magic's tools. Drive Grader finds failing drives, Clone copies one to a new
drive before it dies, Rescue brings back a dead one, and Erase wipes old
drives before they are sold. Boot Repair, Virus Scanner and Password Reset
handle the service calls.

Your construction workers build the hall, its roof and the rooms the crew
lives in. The crew needs food, beds under a roof and time off, and unhappy
people quit. Power outages, heat waves, rain, protesters, copper thieves and
a bear all cause trouble. The site is lost after three dawns in a row below
zero coins, or when the whole crew is gone.

The game has a tutorial that covers the basics.

# Download and play

The game comes as a zip for Windows, macOS or Linux. Each zip has its own
Java runtime, so you do not need to install Java.

1. Unzip it to a folder you can write to, such as your Desktop.
2. Start the game:
   - Windows: `Parted Magic Server Room.exe` in the `Parted Magic Server Room` folder.
   - macOS: `Parted Magic Server Room.app`.
   - Linux: `Parted Magic Server Room/bin/Parted Magic Server Room`.

**Windows:** the game is not code-signed yet, so the first start shows a
SmartScreen warning, "Windows protected your PC". Click **More info**, then
**Run anyway**. Windows remembers the choice.

**macOS:** the Mac version runs on Macs with Apple silicon. It is signed by
Parted Magic LLC and notarized by Apple. The first time you open it, macOS
asks whether to open an app downloaded from the internet. Click **Open**.

Saved games and settings are kept in a `.towns` folder in your home folder.

# Building from source

You need Git and Java 17 or later to run Gradle. On the first build, Gradle
downloads a Java 25 JDK for the game if you do not have one.

Clone this repository, then run `./gradlew run` in it (`gradlew.bat run` on
Windows). The game's pictures, sounds and font are in the repository, so
nothing else is needed.

`./gradlew jpackageZip` builds the release zip for the computer it runs on,
in `build/distributions/`. `./gradlew test` runs the tests. See
[CONTRIBUTING.md](./CONTRIBUTING.md) for more.

# Code License

The code is under the [GNU GPL v3][LICENSE], like the Towns code it is built
on. You can use, study, change and share it. If you share a changed version,
it must also be under the GPL, with its source code available. See the
[LICENSE][] file for the full text.

This game is a changed version of OpenTowns, which is a changed version of
Towns. Parted Magic LLC made its changes in 2026.

Third-party libraries bundled in the packaged releases are credited, with
their licenses, in [THIRD-PARTY-NOTICES.md](./THIRD-PARTY-NOTICES.md).

# Art License

The game's own pictures and sounds, in `src/data/graphics` and `src/data/audio`, are released under the same [GNU GPL v3 license][LICENSE] as the code, except the splash screen.

The splash screen, `src/data/graphics/smplogo.png`, shows the Parted Magic logo. It is © Parted Magic LLC, all rights reserved, and is not released under the GPL. Anyone releasing their own version of the game must replace it with their own picture (`SMP_LOGO_FILE` in `src/graphics.ini` names the file).

As section 7 of GPL v3 allows, no rights are granted to use the Parted Magic name or logo as a trademark.

The text font, `src/data/fonts/cantarell_17.fnt` with its glyph sheet `src/data/graphics/cantarell_17.png`, is made from the Cantarell typeface and stays under its own license, the SIL Open Font License 1.1. See [THIRD-PARTY-NOTICES.md](./THIRD-PARTY-NOTICES.md).

The original Towns assets (its graphics, audio and fonts) are not part of this game. They belong to their authors and must never be added to this repository or any fork of it.

# Credits

Parted Magic Server Room is made by Parted Magic LLC.

It is built on [Towns][Towns game], a town-building game from 2012. Its
developers released the source code under the GNU GPL v3 in 2026. The
[OpenTowns][OpenTowns] project moved that code to Java 25 and LWJGL 3, and
this game started from its work.

[Towns game]: https://store.steampowered.com/app/221020/Towns/
[OpenTowns]: https://github.com/OpenTowns/OpenTowns
[LICENSE]: ./LICENSE
