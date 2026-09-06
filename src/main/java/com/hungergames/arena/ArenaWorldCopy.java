package com.hungergames.arena;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Copies and deletes Minecraft world folders for arena snapshot restore.
 * Skips lock/uid files and per-player data so a loaded world can be snapshotted
 * and later swapped back in as a clean play world.
 */
public final class ArenaWorldCopy {

    private static final Set<String> SKIP_NAMES = Set.of(
            "session.lock",
            "uid.dat",
            "playerdata",
            "advancements",
            "stats"
    );

    private ArenaWorldCopy() {
    }

    public static void deleteRecursively(File folder) throws IOException {
        if (folder == null || !folder.exists()) {
            return;
        }
        Path root = folder.toPath();
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> paths = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path path : paths) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    Path name = path.getFileName();
                    if (name != null && "session.lock".equals(name.toString())) {
                        continue;
                    }
                    throw e;
                }
            }
        }
    }

    public static void copyDirectory(File source, File dest) throws IOException {
        if (source == null || !source.isDirectory()) {
            throw new IOException("Source world folder does not exist: " + source);
        }
        Path fromRoot = source.toPath();
        Path toRoot = dest.toPath();
        Files.createDirectories(toRoot);
        try (Stream<Path> walk = Files.walk(fromRoot)) {
            for (Path from : walk.toList()) {
                Path relative = fromRoot.relativize(from);
                if (shouldSkip(relative)) {
                    continue;
                }
                Path to = toRoot.resolve(relative.toString());
                if (Files.isDirectory(from)) {
                    Files.createDirectories(to);
                } else {
                    if (to.getParent() != null) {
                        Files.createDirectories(to.getParent());
                    }
                    Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static boolean shouldSkip(Path relative) {
        if (relative.getNameCount() == 0) {
            return false;
        }
        for (Path part : relative) {
            if (SKIP_NAMES.contains(part.toString())) {
                return true;
            }
        }
        return false;
    }
}
