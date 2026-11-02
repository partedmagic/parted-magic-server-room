package xaos.setup;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Comparator;
import java.util.stream.Stream;

import org.lwjgl.util.tinyfd.TinyFileDialogs;

/**
 * Startup asset check: the game's own pictures, sounds and font
 * (data/graphics, data/audio, data/fonts) ship with it, and if one is
 * missing run() says so and exits.
 *
 * Runs at the top of Towns.main, before any Game/GLFW/AL initialization;
 * TownsHeadless never calls it. The launcher (xaos.launcher) makes the same
 * check first, making run() a no-op; run() covers -Dtowns.skipLauncher=true
 * and launcher-less startup paths. OpenTowns used this class to find a
 * Steam Towns install and copy Towns' proprietary files in; this game has
 * its own and offers no copy, but the copy helpers below and SteamLocator
 * stay, with their tests. The data/ folder names are hardcoded on purpose:
 * reading GRAPHICS_FOLDER etc. through Towns.getPropertiesString would
 * class-initialize Game. Dialog text avoids quote characters; the macOS
 * tinyfd backend goes through osascript.
 */
public final class FirstRunSetup {

    static final String[] REQUIRED_FOLDERS = { "graphics", "audio", "fonts" };

    private FirstRunSetup() {
    }

    /**
     * The data/ directory the proprietary assets are copied into. Rooted at the
     * app home (towns.home, set by the packaged launcher to $APPDIR) when
     * present, otherwise resolved against the working directory as before. Read
     * straight from the system property, not via Towns, to keep this class free
     * of game-package imports (it must not class-initialize Game or draw RNG).
     */
    public static Path dataDir() {
        String home = System.getProperty("towns.home", "").trim();
        return home.isEmpty() ? Path.of("data") : Path.of(home, "data");
    }

    /** Returns normally when the assets are present; says so and exits the JVM otherwise. */
    public static void run() {
        Path data = dataDir();
        if (assetsPresent(data)) {
            return;
        }
        // No folder in the dialog: tinyfd refuses text with a quote in it,
        // and a path may have one
        System.out.println("Some game files are missing from " + data.toAbsolutePath() + ". Download the game again.");
        error("Some game files are missing.\nDownload the game again.");
        System.exit(1);
    }

    /** True when data/graphics, data/audio and data/fonts all exist and are non-empty. */
    public static boolean assetsPresent(Path data) {
        for (String name : REQUIRED_FOLDERS) {
            if (!isNonEmptyDir(data.resolve(name))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Accepts either the Towns install root (contains data/graphics and
     * data/fonts) or a data folder directly (contains graphics/ and fonts/).
     * Returns the normalized data root, or null when the folder does not look
     * like a Towns install.
     */
    public static Path validateSelection(Path picked) {
        if (picked == null) {
            return null;
        }
        Path asRoot = picked.resolve("data");
        if (Files.isDirectory(asRoot.resolve("graphics")) && Files.isDirectory(asRoot.resolve("fonts"))) {
            return asRoot;
        }
        if (Files.isDirectory(picked.resolve("graphics")) && Files.isDirectory(picked.resolve("fonts"))) {
            return picked;
        }
        return null;
    }

    /**
     * Copies each missing or empty required folder from sourceData into
     * targetData. Each folder is copied to a sibling <name>.tmp first and then
     * renamed into place, so an interrupted copy can never look like a complete
     * install on the next run. Folders already present and non-empty are kept.
     */
    public static void copyAssets(Path sourceData, Path targetData) throws IOException {
        Files.createDirectories(targetData);
        for (String name : REQUIRED_FOLDERS) {
            Path target = targetData.resolve(name);
            Path tmp = targetData.resolve(name + ".tmp");
            deleteRecursively(tmp); // stale tmp from an interrupted earlier run
            if (isNonEmptyDir(target)) {
                continue;
            }
            Path source = sourceData.resolve(name);
            if (!Files.isDirectory(source)) {
                throw new IOException("Missing folder in the Towns install: " + source);
            }
            System.out.print("Copying " + name + "...");
            try {
                copyRecursively(source, tmp);
            } catch (IOException e) {
                try {
                    deleteRecursively(tmp);
                } catch (IOException ignored) {
                }
                throw e;
            }
            deleteRecursively(target); // may exist but empty
            Files.move(tmp, target);
            System.out.println(" done");
        }
    }

    private static boolean isNonEmptyDir(Path dir) {
        if (!Files.isDirectory(dir)) {
            return false;
        }
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.findFirst().isPresent();
        } catch (IOException e) {
            return false;
        }
    }

    private static void copyRecursively(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(target.resolve(source.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, target.resolve(source.relativize(file)), StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> tree = Files.walk(root)) {
            for (Path path : tree.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    // --- the dialog: the only tinyfd touchpoint, never reached by tests ---

    private static void error(String message) {
        TinyFileDialogs.tinyfd_messageBox("Parted Magic Server Room", message, "ok", "error", true);
    }
}
