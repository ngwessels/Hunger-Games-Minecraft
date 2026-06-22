package com.hungergames.terrain;

import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;

import java.util.Random;

/**
 * Treats expanding volcano rock as hot enough to ignite nearby wood and leaves.
 */
public final class VolcanoHeatUtil {

    private static final BlockFace[] IGNITE_FACES = {
            BlockFace.UP, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST
    };
    private static final int COLUMN_SCAN_ABOVE = 32;

    private VolcanoHeatUtil() {
    }

    public static boolean isHotVolcanoBlock(Material type) {
        return type == Material.NETHERRACK
                || type == Material.MAGMA_BLOCK
                || type == Material.BLACKSTONE;
    }

    public static boolean isFlammableTreeBlock(Material type) {
        return Tag.LOGS.isTagged(type) || Tag.LEAVES.isTagged(type);
    }

    public static boolean isFoliage(Material type) {
        return Tag.LOGS.isTagged(type) || Tag.LEAVES.isTagged(type)
                || type == Material.TALL_GRASS || type == Material.SHORT_GRASS
                || type == Material.DEAD_BUSH || type == Material.FIRE;
    }

    /** Top solid block in a column, ignoring logs, leaves, and other foliage. */
    public static int findTerrainGroundY(World world, int x, int z, int scanBaseY) {
        for (int y = scanBaseY + 80; y >= 1; y--) {
            Material type = world.getBlockAt(x, y, z).getType();
            if (!type.isSolid() || type == Material.LAVA || isFoliage(type)) {
                continue;
            }
            return y;
        }
        return scanBaseY;
    }

    /** Ignite wood/leaves touching a newly placed hot volcano block. */
    public static void igniteFlammablesNearHotBlock(World world, int x, int y, int z) {
        Block hot = world.getBlockAt(x, y, z);
        if (!isHotVolcanoBlock(hot.getType())) {
            return;
        }
        igniteFlammablesAdjacentTo(hot);
        for (int dy = 1; dy <= COLUMN_SCAN_ABOVE; dy++) {
            Block above = world.getBlockAt(x, y + dy, z);
            Material type = above.getType();
            if (isFlammableTreeBlock(type)) {
                tryIgnite(above);
            }
        }
    }

    /**
     * Rotating sweep over the volcano footprint: ignite logs/leaves that touch hot rock.
     * Returns how many block checks were performed.
     */
    public static int tickIgniteSweep(World world, double centerX, double centerZ,
                                      int minY, int maxY, int scanRadius, int budget, int[] cursor) {
        if (budget <= 0 || scanRadius <= 0 || minY > maxY) {
            return 0;
        }

        int checks = 0;
        int cx = (int) centerX;
        int cz = (int) centerZ;
        int radiusSq = scanRadius * scanRadius;
        int side = 2 * scanRadius + 1;
        int totalCells = side * side;
        if (totalCells <= 0) {
            return 0;
        }

        int startCursor = cursor[0];
        while (checks < budget) {
            int idx = cursor[0] % totalCells;
            cursor[0]++;
            if (cursor[0] - startCursor > totalCells) {
                break;
            }

            int dx = idx % side - scanRadius;
            int dz = idx / side - scanRadius;
            if (dx * dx + dz * dz > radiusSq) {
                continue;
            }

            int x = cx + dx;
            int z = cz + dz;
            for (int y = minY; y <= maxY; y++) {
                checks++;
                if (checks > budget) {
                    return checks;
                }
                Block block = world.getBlockAt(x, y, z);
                if (!isFlammableTreeBlock(block.getType())) {
                    continue;
                }
                if (hasAdjacentHotBlock(block)) {
                    tryIgnite(block);
                }
            }
        }
        return checks;
    }

    /**
     * Rotating sweep over the arena: accelerate burning logs/leaves adjacent to fire.
     * Returns how many block checks were performed.
     */
    @FunctionalInterface
    public interface BlockPositionFilter {
        boolean shouldSkip(int x, int y, int z);
    }

    public static int tickBurnSweep(World world, double centerX, double centerZ,
                                    int minY, int maxY, int scanRadius, int budget, int[] cursor,
                                    double burnChancePerCheck, Random random, BlockPositionFilter skipBlock) {
        if (budget <= 0 || scanRadius <= 0 || minY > maxY || burnChancePerCheck <= 0) {
            return 0;
        }

        int checks = 0;
        int cx = (int) centerX;
        int cz = (int) centerZ;
        int radiusSq = scanRadius * scanRadius;
        int side = 2 * scanRadius + 1;
        int totalCells = side * side;
        if (totalCells <= 0) {
            return 0;
        }

        int startCursor = cursor[0];
        while (checks < budget) {
            int idx = cursor[0] % totalCells;
            cursor[0]++;
            if (cursor[0] - startCursor > totalCells) {
                break;
            }

            int dx = idx % side - scanRadius;
            int dz = idx / side - scanRadius;
            if (dx * dx + dz * dz > radiusSq) {
                continue;
            }

            int x = cx + dx;
            int z = cz + dz;
            for (int y = minY; y <= maxY; y++) {
                checks++;
                if (checks > budget) {
                    return checks;
                }
                if (skipBlock != null && skipBlock.shouldSkip(x, y, z)) {
                    continue;
                }
                Block block = world.getBlockAt(x, y, z);
                if (!isFlammableTreeBlock(block.getType()) || !hasAdjacentFire(block)) {
                    continue;
                }
                if (random.nextDouble() <= burnChancePerCheck) {
                    burnFlammableBlock(block);
                }
            }
        }
        return checks;
    }

    public static void igniteFlammablesAdjacentTo(Block hotBlock) {
        if (!isHotVolcanoBlock(hotBlock.getType())) {
            return;
        }
        for (BlockFace face : BlockFace.values()) {
            Block neighbor = hotBlock.getRelative(face);
            if (isFlammableTreeBlock(neighbor.getType())) {
                tryIgnite(neighbor);
            }
        }
    }

    private static boolean hasAdjacentHotBlock(Block block) {
        for (BlockFace face : BlockFace.values()) {
            if (isHotVolcanoBlock(block.getRelative(face).getType())) {
                return true;
            }
        }
        return false;
    }

    public static void tryIgnite(Block block) {
        if (!isFlammableTreeBlock(block.getType())) {
            return;
        }
        for (BlockFace face : IGNITE_FACES) {
            Block relative = block.getRelative(face);
            if (tryPlaceSustainedFire(relative)) {
                return;
            }
        }
        Block below = block.getRelative(BlockFace.DOWN);
        if (tryPlaceSustainedFire(below)) {
            return;
        }
        // Dense canopy / trunk with no adjacent air — burn away without leaving orphan fire.
        block.setType(Material.AIR, false);
    }

    public static boolean hasAdjacentFire(Block block) {
        for (BlockFace face : BlockFace.values()) {
            Material type = block.getRelative(face).getType();
            if (type == Material.FIRE || type == Material.SOUL_FIRE) {
                return true;
            }
        }
        return false;
    }

    /** True when fire should remain (touching burnable fuel or hot volcano rock). */
    public static boolean hasFireFuel(Block fireBlock) {
        Material self = fireBlock.getType();
        if (self != Material.FIRE && self != Material.SOUL_FIRE) {
            return false;
        }
        for (BlockFace face : BlockFace.values()) {
            Material type = fireBlock.getRelative(face).getType();
            if (isFlammableTreeBlock(type) || isHotVolcanoBlock(type)
                    || type == Material.LAVA || type == Material.MAGMA_BLOCK) {
                return true;
            }
        }
        return false;
    }

    /**
     * Rotating sweep: remove fire that no longer touches fuel (orphaned after trees burn away).
     * Returns how many block checks were performed.
     */
    public static int tickFireDecaySweep(World world, double centerX, double centerZ,
                                         int minY, int maxY, int scanRadius, int budget, int[] cursor,
                                         double decayChancePerCheck, Random random,
                                         BlockPositionFilter skipBlock) {
        if (budget <= 0 || scanRadius <= 0 || minY > maxY || decayChancePerCheck <= 0) {
            return 0;
        }

        int checks = 0;
        int cx = (int) centerX;
        int cz = (int) centerZ;
        int radiusSq = scanRadius * scanRadius;
        int side = 2 * scanRadius + 1;
        int totalCells = side * side;
        if (totalCells <= 0) {
            return 0;
        }

        int startCursor = cursor[0];
        while (checks < budget) {
            int idx = cursor[0] % totalCells;
            cursor[0]++;
            if (cursor[0] - startCursor > totalCells) {
                break;
            }

            int dx = idx % side - scanRadius;
            int dz = idx / side - scanRadius;
            if (dx * dx + dz * dz > radiusSq) {
                continue;
            }

            int x = cx + dx;
            int z = cz + dz;
            for (int y = minY; y <= maxY; y++) {
                checks++;
                if (checks > budget) {
                    return checks;
                }
                if (skipBlock != null && skipBlock.shouldSkip(x, y, z)) {
                    continue;
                }
                Block block = world.getBlockAt(x, y, z);
                Material type = block.getType();
                if (type != Material.FIRE && type != Material.SOUL_FIRE) {
                    continue;
                }
                if (hasFireFuel(block)) {
                    continue;
                }
                if (random.nextDouble() <= decayChancePerCheck) {
                    block.setType(Material.AIR, false);
                }
            }
        }
        return checks;
    }

    /** Destroy a log/leaf block; only leave fire on air cells that still touch fuel. */
    public static void burnFlammableBlock(Block block) {
        if (!isFlammableTreeBlock(block.getType())) {
            return;
        }
        for (BlockFace face : IGNITE_FACES) {
            Block relative = block.getRelative(face);
            if (relative.getType().isAir() && canSustainFireAt(relative, block)) {
                relative.setType(Material.FIRE, false);
            }
        }
        block.setType(Material.AIR, false);
    }

    /** Place fire on an air cell only when it would touch burnable fuel or hot rock. */
    public static boolean tryPlaceSustainedFire(Block airBlock) {
        if (!airBlock.getType().isAir()) {
            return false;
        }
        if (!canSustainFireAt(airBlock, null)) {
            return false;
        }
        airBlock.setType(Material.FIRE, false);
        return true;
    }

    private static boolean canSustainFireAt(Block airBlock, Block exclude) {
        if (!airBlock.getType().isAir()) {
            return false;
        }
        for (BlockFace face : BlockFace.values()) {
            Block neighbor = airBlock.getRelative(face);
            if (exclude != null && neighbor.equals(exclude)) {
                continue;
            }
            Material type = neighbor.getType();
            if (isFlammableTreeBlock(type) || isHotVolcanoBlock(type)
                    || type == Material.LAVA || type == Material.MAGMA_BLOCK) {
                return true;
            }
        }
        return false;
    }
}
