package xaos.test;

import java.util.Map;

/**
 * Golden pins: the expected world state for fixed seed/map/tick scenarios.
 * They freeze the game's behavior itself, not just run-to-run determinism:
 * any change to worldgen, the sim, or the hash definition in
 * TownsHeadless.computeStateHash shows up as a pin mismatch.
 *
 * First recorded on the original dev machine (Windows, Adoptium JDK 25)
 * while the source was at its original (pre-refactor) behavior.  Recorded
 * again on 2026-10-30 for Parted Magic Server Room, whose data center
 * rules, sites and starting kit change every scenario (Linux, OpenJDK 25),
 * from TownsHeadless process runs as HeadlessRunner starts them, and again
 * after each change to the rules that day (the last for the rework of the
 * night: daily supplier, materials on order, rain, erase contracts).  CI
 * (.github/workflows/test.yml) runs the suite on Windows, Linux and macOS
 * runners, and every OS is expected to reproduce these pins identically;
 * the matrix keeps fail-fast off so a per-OS divergence (file listing
 * order, default locale, floating point) reports on its own instead of
 * being cancelled by another OS's failure.
 */
final class Golden {

    private Golden() {
    }

    private static final Map<String, String> PINS = Map.ofEntries(
            // Worldgen only (seed 42, 0 ticks), one per map type
            Map.entry("worldgen.normal.terrain", "e36b764e83244a8a"),
            Map.entry("worldgen.normal.state", "f06473fa91570acc"),
            Map.entry("worldgen.desert.terrain", "3bc6d27791b7e644"),
            Map.entry("worldgen.desert.state", "b82904c590e075a9"),
            Map.entry("worldgen.jungle.terrain", "d5ba211cb0fba51d"),
            Map.entry("worldgen.jungle.state", "23a5aad9cc06f9c6"),
            Map.entry("worldgen.mixed.terrain", "31f67c5b8bddb891"),
            Map.entry("worldgen.mixed.state", "28d3366c7e7dd2d7"),
            Map.entry("worldgen.snow.terrain", "10e3eec9137c201f"),
            Map.entry("worldgen.snow.state", "416907e063d1bc95"),
            Map.entry("worldgen.mountains.terrain", "a03e68bb147142f9"),
            Map.entry("worldgen.mountains.state", "fd07e8501006055e"),

            // Simulated scenarios (seed, map, ticks)
            Map.entry("sim.42.normal.3000.terrain", "3001723288af222"),
            Map.entry("sim.42.normal.3000.state", "7c84385cf64e57f3"),
            Map.entry("sim.42.normal.3000.summary", "date=1/1/1 citizens=10 livings=324 items=736 coins=61"),
            Map.entry("sim.1234.desert.3000.terrain", "ec17f7c16d34683c"),
            Map.entry("sim.1234.desert.3000.state", "5b178efacb3e7391"),
            Map.entry("sim.1234.desert.3000.summary", "date=1/1/1 citizens=10 livings=150 items=342 coins=59"),
            Map.entry("sim.42.normal.20000.terrain", "e260cdff2c9d1172"),
            Map.entry("sim.42.normal.20000.state", "72823956c2b9a4a3"),
            Map.entry("sim.42.normal.20000.summary", "date=3/1/1 citizens=10 livings=324 items=726 coins=129"),

            // Pinned from a process run; LongRunSmokeTest asserts them
            // in-JVM, which also proves the two drive paths are equivalent
            Map.entry("sim.4242.normal.20000.terrain", "30e700766290ab34"),
            Map.entry("sim.4242.normal.20000.state", "f6defe21b4d47940"),

            // L1 scripted-directive scenario (ScenarioMineTest): one mine order
            // at 69,160,12, seed 42 / normal, 1000 ticks. Freezes active-play
            // behavior, not just free-run drift.
            Map.entry("scenario.mine.42.normal.t250.state", "70dd60b76025afe"),
            Map.entry("scenario.mine.42.normal.terrain", "721301aa29be95bf"),
            Map.entry("scenario.mine.42.normal.state", "c3a054b923da8704"));

    static String get(String key) {
        String value = PINS.get(key);
        if (value == null) {
            throw new IllegalStateException("no golden pin recorded for " + key);
        }
        return value;
    }
}
