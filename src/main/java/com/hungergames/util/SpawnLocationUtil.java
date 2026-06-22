package com.hungergames.util;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;

public final class SpawnLocationUtil {

    private static final int MAX_SEARCH_RADIUS = 12;

    private SpawnLocationUtil() {
    }

    public static Location findSafeSpawn(World world, int centerX, int centerZ, int searchFromY) {
        for (int radius = 0; radius <= MAX_SEARCH_RADIUS; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (radius > 0 && Math.abs(dx) != radius && Math.abs(dz) != radius) {
                        continue;
                    }
                    int x = centerX + dx;
                    int z = centerZ + dz;
                    int feetY = findGroundY(world, x, z, searchFromY);
                    if (isSafePlayerSpawn(world, x, feetY, z)) {
                        return new Location(world, x + 0.5, feetY + 0.5, z + 0.5);
                    }
                }
            }
        }

        int fallbackY = Math.max(1, findGroundY(world, centerX, centerZ, searchFromY));
        return new Location(world, centerX + 0.5, fallbackY + 0.5, centerZ + 0.5);
    }

    public static int findGroundY(World world, int x, int z, int searchFromY) {
        int top = searchFromY + 28;
        for (int y = top; y >= 1; y--) {
            if (canStandAt(world, x, y, z)) {
                return y;
            }
        }
        return searchFromY;
    }

    public static boolean isSafePlayerSpawn(World world, int x, int feetY, int z) {
        return isSafeRespawnLocation(world, x, feetY, z);
    }

    public static boolean isSafeRespawnLocation(World world, int x, int feetY, int z) {
        if (feetY < world.getMinHeight() + 1) {
            return false;
        }
        if (!canStandAt(world, x, feetY, z)) {
            return false;
        }
        if (!hasStableFooting(world, x, feetY, z)) {
            return false;
        }

        for (int dx = 0; dx <= 1; dx++) {
            for (int dz = 0; dz <= 1; dz++) {
                for (int dy = -1; dy <= 2; dy++) {
                    Material type = world.getBlockAt(x + dx, feetY + dy, z + dz).getType();
                    if (isSpawnObstruction(type) || isHazardousMaterial(type)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static boolean hasStableFooting(World world, int x, int feetY, int z) {
        for (int dx = 0; dx <= 1; dx++) {
            for (int dz = 0; dz <= 1; dz++) {
                Material ground = world.getBlockAt(x + dx, feetY - 1, z + dz).getType();
                if (!isGroundMaterial(ground) || isHazardousMaterial(ground)) {
                    return false;
                }
            }
        }
        return true;
    }

    public static boolean canStandAt(World world, int x, int feetY, int z) {
        Material ground = world.getBlockAt(x, feetY - 1, z).getType();
        Material feet = world.getBlockAt(x, feetY, z).getType();
        Material head = world.getBlockAt(x, feetY + 1, z).getType();
        return isGroundMaterial(ground) && isPassableForPlayer(feet) && isPassableForPlayer(head);
    }

    public static boolean isOnSpawnRing(double x, double z, double centerX, double centerZ, int arenaRadius) {
        double spawnRadius = arenaRadius * 0.8;
        double dx = x + 0.5 - centerX;
        double dz = z + 0.5 - centerZ;
        double dist = Math.sqrt(dx * dx + dz * dz);
        return Math.abs(dist - spawnRadius) <= 5;
    }

    /** True when a loot chest can sit on this block (surface ground, not lava or fluids). */
    public static boolean isLootChestGround(Material type) {
        return isGroundMaterial(type) && !isHazardousMaterial(type);
    }

    /** True when the block at chest height can be replaced (air or surface vegetation). */
    public static boolean isLootChestReplaceable(Material type) {
        if (type.isAir()) {
            return true;
        }
        return switch (type) {
            case SHORT_GRASS, TALL_GRASS, FERN, LARGE_FERN, DEAD_BUSH, SNOW -> true;
            default -> false;
        };
    }

    /** Chest at (x, y, z) sits on valid ground with a replaceable block at feet level. */
    public static boolean canPlaceLootChest(World world, int x, int y, int z) {
        return isLootChestGround(world.getBlockAt(x, y - 1, z).getType())
                && isLootChestReplaceable(world.getBlockAt(x, y, z).getType());
    }

    private static boolean isGroundMaterial(Material type) {
        if (type.isAir() || !type.isSolid()) {
            return false;
        }
        if (Tag.LEAVES.isTagged(type) || Tag.LOGS.isTagged(type)) {
            return false;
        }
        return switch (type) {
            case TALL_GRASS, SHORT_GRASS, FERN, LARGE_FERN, DEAD_BUSH,
                 SNOW, VINE, LILY_PAD, COBWEB -> false;
            default -> true;
        };
    }

    private static boolean isPassableForPlayer(Material type) {
        return !isSpawnObstruction(type);
    }

    private static boolean isSpawnObstruction(Material type) {
        if (type.isAir()) {
            return false;
        }
        if (type == Material.SHORT_GRASS || type == Material.TALL_GRASS
                || type == Material.FERN || type == Material.LARGE_FERN
                || type == Material.DEAD_BUSH || type == Material.SNOW) {
            return false;
        }
        return type.isSolid() || Tag.LEAVES.isTagged(type) || Tag.LOGS.isTagged(type);
    }

    private static boolean isHazardousMaterial(Material type) {
        if (type.isAir()) {
            return false;
        }
        if (Tag.FIRE.isTagged(type)) {
            return true;
        }
        return switch (type) {
            case LAVA, WATER, MAGMA_BLOCK, CACTUS, SWEET_BERRY_BUSH, WITHER_ROSE,
                 POWDER_SNOW, POINTED_DRIPSTONE, ANVIL, CHIPPED_ANVIL, DAMAGED_ANVIL -> true;
            default -> false;
        };
    }
}
