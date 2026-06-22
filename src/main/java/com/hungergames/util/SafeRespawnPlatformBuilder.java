package com.hungergames.util;

import com.hungergames.arena.Arena;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.ArrayList;
import java.util.List;

/**
 * Finds a natural safe respawn point, or builds a temporary platform (obsidian footing,
 * water-on-lava conversion, fire removal) when the terrain is too dangerous.
 */
public final class SafeRespawnPlatformBuilder {

    private static final int MAX_SEARCH_RADIUS = 24;

    private SafeRespawnPlatformBuilder() {
    }

    public static SafeRespawnPlacement findOrCreate(
            World world, int anchorX, int anchorZ, int searchFromY, Arena arena) {
        Location natural = tryFindNaturalSafeRespawn(world, anchorX, anchorZ, searchFromY, arena);
        if (natural != null) {
            return SafeRespawnPlacement.natural(natural);
        }

        int[] site = findBestBuildSite(world, anchorX, anchorZ, searchFromY, arena);
        int x = site[0];
        int z = site[1];
        int feetY = site[2];
        List<BlockSnapshot> snapshots = new ArrayList<>();
        buildPlatform(world, x, z, feetY, snapshots);
        return SafeRespawnPlacement.built(
                new Location(world, x + 0.5, feetY + 0.5, z + 0.5), snapshots);
    }

    public static void restore(World world, List<BlockSnapshot> snapshots) {
        if (snapshots == null || snapshots.isEmpty()) {
            return;
        }
        for (BlockSnapshot snapshot : snapshots) {
            world.getBlockAt(snapshot.x(), snapshot.y(), snapshot.z())
                    .setType(snapshot.material(), false);
        }
    }

    private static Location tryFindNaturalSafeRespawn(
            World world, int centerX, int centerZ, int searchFromY, Arena arena) {
        for (int radius = 0; radius <= MAX_SEARCH_RADIUS; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (radius > 0 && Math.abs(dx) != radius && Math.abs(dz) != radius) {
                        continue;
                    }
                    int x = centerX + dx;
                    int z = centerZ + dz;
                    if (arena != null && !ArenaBounds.isInsidePlayArea(arena, x + 0.5, z + 0.5)) {
                        continue;
                    }
                    int feetY = SpawnLocationUtil.findGroundY(world, x, z, searchFromY);
                    if (SpawnLocationUtil.isSafeRespawnLocation(world, x, feetY, z)) {
                        return new Location(world, x + 0.5, feetY + 0.5, z + 0.5);
                    }
                }
            }
        }
        return null;
    }

    private static int[] findBestBuildSite(
            World world, int anchorX, int anchorZ, int searchFromY, Arena arena) {
        for (int radius = 0; radius <= MAX_SEARCH_RADIUS; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (radius > 0 && Math.abs(dx) != radius && Math.abs(dz) != radius) {
                        continue;
                    }
                    int x = anchorX + dx;
                    int z = anchorZ + dz;
                    if (arena != null && !ArenaBounds.isInsidePlayArea(arena, x + 0.5, z + 0.5)) {
                        continue;
                    }
                    int feetY = resolveBuildFeetY(world, x, z, searchFromY);
                    return new int[]{x, z, feetY};
                }
            }
        }

        int feetY = resolveBuildFeetY(world, anchorX, anchorZ, searchFromY);
        return new int[]{anchorX, anchorZ, feetY};
    }

    private static int resolveBuildFeetY(World world, int x, int z, int searchFromY) {
        int top = searchFromY + 28;
        for (int y = top; y >= world.getMinHeight() + 2; y--) {
            Material below = world.getBlockAt(x, y - 1, z).getType();
            if (isSolidSupport(below) && !isHazardous(below)) {
                return y;
            }
        }

        for (int y = top; y >= world.getMinHeight() + 2; y--) {
            Material at = world.getBlockAt(x, y, z).getType();
            if (at.isAir() || isPassable(at)) {
                return y;
            }
        }

        return Math.max(world.getMinHeight() + 2, searchFromY + 1);
    }

    private static void buildPlatform(World world, int x, int z, int feetY, List<BlockSnapshot> snapshots) {
        neutralizeHazards(world, x, z, feetY, snapshots);

        for (int dx = 0; dx <= 1; dx++) {
            for (int dz = 0; dz <= 1; dz++) {
                int groundX = x + dx;
                int groundZ = z + dz;
                replaceBlock(world, groundX, feetY - 1, groundZ, Material.OBSIDIAN, snapshots);
                replaceBlock(world, groundX, feetY, groundZ, Material.AIR, snapshots);
                replaceBlock(world, groundX, feetY + 1, groundZ, Material.AIR, snapshots);
            }
        }
    }

    private static void neutralizeHazards(World world, int x, int z, int feetY, List<BlockSnapshot> snapshots) {
        for (int dx = -1; dx <= 2; dx++) {
            for (int dz = -1; dz <= 2; dz++) {
                for (int dy = -2; dy <= 3; dy++) {
                    int bx = x + dx;
                    int by = feetY + dy;
                    int bz = z + dz;
                    Material type = world.getBlockAt(bx, by, bz).getType();
                    Material replacement = hazardReplacement(type, by < feetY);
                    if (replacement != null) {
                        replaceBlock(world, bx, by, bz, replacement, snapshots);
                    }
                }
            }
        }
    }

    private static Material hazardReplacement(Material type, boolean belowFeet) {
        if (type.isAir()) {
            return null;
        }
        if (Tag.FIRE.isTagged(type)) {
            return Material.AIR;
        }
        if (type == Material.LAVA) {
            return belowFeet ? Material.OBSIDIAN : Material.WATER;
        }
        if (type == Material.MAGMA_BLOCK) {
            return belowFeet ? Material.OBSIDIAN : Material.COBBLESTONE;
        }
        return switch (type) {
            case WATER, CACTUS, SWEET_BERRY_BUSH, WITHER_ROSE, POWDER_SNOW,
                 POINTED_DRIPSTONE, COBWEB -> Material.AIR;
            default -> null;
        };
    }

    private static void replaceBlock(
            World world, int x, int y, int z, Material material, List<BlockSnapshot> snapshots) {
        Block block = world.getBlockAt(x, y, z);
        Material original = block.getType();
        if (original == material) {
            return;
        }
        snapshots.add(new BlockSnapshot(x, y, z, original));
        block.setType(material, false);
    }

    private static boolean isSolidSupport(Material type) {
        return type.isSolid() && !isHazardous(type);
    }

    private static boolean isPassable(Material type) {
        return type.isAir() || !type.isSolid() || type == Material.SHORT_GRASS || type == Material.TALL_GRASS;
    }

    private static boolean isHazardous(Material type) {
        if (type.isAir()) {
            return false;
        }
        if (Tag.FIRE.isTagged(type)) {
            return true;
        }
        return switch (type) {
            case LAVA, MAGMA_BLOCK, CACTUS, SWEET_BERRY_BUSH, WITHER_ROSE, POWDER_SNOW -> true;
            default -> false;
        };
    }
}
