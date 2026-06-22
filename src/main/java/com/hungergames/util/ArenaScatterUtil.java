package com.hungergames.util;

import com.hungergames.HungerGamesPlugin;
import com.hungergames.arena.Arena;
import com.hungergames.arena.ArenaGenerator;
import com.hungergames.arena.VolcanoCaldera;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public final class ArenaScatterUtil {

    private ArenaScatterUtil() {
    }

    public static List<Location> findRandomLocations(HungerGamesPlugin plugin, Arena arena, int count) {
        return findRandomLocations(plugin, arena, count, null, null, false);
    }

    public static List<Location> findRandomLocations(
            HungerGamesPlugin plugin,
            Arena arena,
            int count,
            List<Location> existing,
            Double minSpacingOverride) {
        return findRandomLocations(plugin, arena, count, existing, minSpacingOverride, false);
    }

    public static List<Location> findRandomLocations(
            HungerGamesPlugin plugin,
            Arena arena,
            int count,
            List<Location> existing,
            Double minSpacingOverride,
            boolean volcanoTerrain) {
        List<Location> results = new ArrayList<>();
        World world = arena.getWorld();
        if (world == null || count <= 0) {
            return results;
        }

        ArenaGenerator generator = plugin.getArenaGenerator();
        Random random = new Random();
        double cx = arena.getCenterX();
        double cz = arena.getCenterZ();
        int maxRadius = arena.getRadius() - 5;
        double minRadius = volcanoTerrain
                ? VolcanoCaldera.NO_TREE_RADIUS + 2
                : Math.max(0, plugin.getPluginConfig().getLootBoxMinDistanceFromCenter());
        double minRadiusSq = minRadius * minRadius;
        double maxRadiusSq = (double) maxRadius * maxRadius;
        double minSpacingSq = minSpacingOverride != null
                ? minSpacingOverride * minSpacingOverride
                : plugin.getPluginConfig().getLootBoxMinSpacing();
        minSpacingSq *= minSpacingSq;
        int baseY = (int) arena.getCenterY();
        int attempts = 0;
        int maxAttempts = count * 80;

        List<Location> placed = existing != null ? new ArrayList<>(existing) : new ArrayList<>();

        while (results.size() < count && attempts < maxAttempts) {
            attempts++;
            double angle = random.nextDouble() * Math.PI * 2;
            double dist = Math.sqrt(minRadiusSq + random.nextDouble() * (maxRadiusSq - minRadiusSq));
            int x = (int) (cx + Math.cos(angle) * dist);
            int z = (int) (cz + Math.sin(angle) * dist);

            if (!CircleUtil.isInsideCircle(cx, cz, x, z, maxRadius)) {
                continue;
            }

            ensureChunkLoaded(world, x, z);
            int y = generator.findSurfaceAt(world, x, z, baseY);
            if (!SpawnLocationUtil.canPlaceLootChest(world, x, y, z)) {
                continue;
            }
            Location loc = new Location(world, x + 0.5, y, z + 0.5);
            if (!isFarEnough(loc, placed, minSpacingSq)) {
                continue;
            }
            results.add(loc);
            placed.add(loc);
        }
        return results;
    }

    public static Location findCalderaLocation(
            Arena arena,
            double centerX,
            double centerZ,
            int baseY,
            double minRadius,
            double maxRadius,
            ArenaGenerator generator) {
        World world = arena.getWorld();
        if (world == null) {
            return null;
        }

        Random random = new Random();
        double minRadiusSq = minRadius * minRadius;
        double maxRadiusSq = maxRadius * maxRadius;
        int attempts = 0;
        while (attempts++ < 40) {
            double angle = random.nextDouble() * Math.PI * 2;
            double dist = Math.sqrt(minRadiusSq + random.nextDouble() * (maxRadiusSq - minRadiusSq));
            int x = (int) (centerX + Math.cos(angle) * dist);
            int z = (int) (centerZ + Math.sin(angle) * dist);
            if (!CircleUtil.isInsideCircle(centerX, centerZ, x, z, arena.getRadius() - 5)) {
                continue;
            }
            ensureChunkLoaded(world, x, z);
            int y = generator.findSurfaceAt(world, x, z, baseY);
            if (!SpawnLocationUtil.canPlaceLootChest(world, x, y, z)) {
                continue;
            }
            return new Location(world, x + 0.5, y, z + 0.5);
        }
        return null;
    }

    private static boolean isFarEnough(Location candidate, List<Location> placed, double minSpacingSq) {
        for (Location existing : placed) {
            if (candidate.distanceSquared(existing) < minSpacingSq) {
                return false;
            }
        }
        return true;
    }

    private static void ensureChunkLoaded(World world, int x, int z) {
        Chunk chunk = world.getChunkAt(x >> 4, z >> 4);
        if (!chunk.isLoaded()) {
            chunk.load(true);
        }
    }
}
