package com.hungergames.terrain;

import com.hungergames.arena.Arena;
import com.hungergames.arena.VolcanoCaldera;
import com.hungergames.gamemode.TerrainPhaseConfig;
import com.hungergames.util.CircleUtil;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Levelled;
import org.bukkit.util.BlockVector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Trenches that erupt from the volcano's base and carve outward across the arena.
 * Each trench slowly cuts a channel into the terrain; lava follows behind the
 * digging front with a delay, gradually flooding the completed sections.
 */
public class VolcanoLavaFlow {

    private static final Set<Material> CARVEABLE = Set.of(
            Material.GRASS_BLOCK, Material.DIRT, Material.COARSE_DIRT, Material.PODZOL,
            Material.STONE, Material.BASALT, Material.SMOOTH_BASALT, Material.NETHERRACK,
            Material.BLACKSTONE, Material.COBBLESTONE, Material.TUFF, Material.DEEPSLATE,
            Material.GRAVEL, Material.SAND, Material.SANDSTONE, Material.MAGMA_BLOCK,
            Material.OAK_LOG, Material.OAK_LEAVES, Material.SPRUCE_LOG, Material.SPRUCE_LEAVES,
            Material.BIRCH_LOG, Material.BIRCH_LEAVES,
            Material.TALL_GRASS, Material.SHORT_GRASS, Material.DEAD_BUSH, Material.FIRE
    );

    private final List<Trench> trenches = new ArrayList<>();
    private final Set<BlockVector> trackedSources = new LinkedHashSet<>();
    private final Random random = new Random();

    private double cx;
    private double cz;
    private int scanBaseY;
    private int volcanoProtectRadius;

    public void initialize(World world, double centerX, double centerZ, int surfaceY, Random rng) {
        this.cx = centerX;
        this.cz = centerZ;
        this.scanBaseY = surfaceY;
        this.random.setSeed(rng.nextLong());
        trenches.clear();
        trackedSources.clear();
    }

    public void tick(World world, Arena arena, TerrainPhaseConfig config, long eruptionStartMs,
                     double matchProgress, int volcanoRadius, double flowSpeedMultiplier) {
        long elapsedSec = Math.max(0, (System.currentTimeMillis() - eruptionStartMs) / 1000L);
        double progress = resolveProgress(config, elapsedSec, matchProgress);

        validateTrackedSources(world);

        int maxTrenches = config.getInt("lavaTrenchMax", 10);
        int targetTrenches = Math.min(maxTrenches, 1 + (int) Math.floor(progress * maxTrenches));
        if (trenches.size() < targetTrenches) {
            spawnTrench(arena, config, volcanoRadius);
        }

        int carvePerTick = config.getInt("lavaTrenchCarvePerTick", 3);
        int fillPerTick = scaleFillRate(config.getInt("lavaTrenchFillPerTick", 2), flowSpeedMultiplier);
        int depth = Math.max(1, config.getInt("lavaTrenchDepth", 2));
        int lavaLag = scaleLag(config.getInt("lavaTrenchLavaLagCells", 8), flowSpeedMultiplier);
        int maxActiveSources = computeMaxActiveSources(config, arena, progress);
        volcanoProtectRadius = volcanoRadius;

        for (Trench trench : trenches) {
            extendTrench(world, arena, trench, carvePerTick, depth);
            fillTrench(world, trench, fillPerTick, lavaLag, maxActiveSources);
        }
        pruneExcessSources(world, arena, maxActiveSources);
    }

    private double resolveProgress(TerrainPhaseConfig config, long elapsedSec, double matchProgress) {
        if (matchProgress >= 0) {
            return Math.min(1.0, matchProgress);
        }
        int rampSeconds = config.getInt("lavaRampSeconds", 900);
        return Math.min(1.0, elapsedSec / (double) Math.max(1, rampSeconds));
    }

    private int computeMaxActiveSources(TerrainPhaseConfig config, Arena arena, double progress) {
        int configuredCap = config.getInt("maxActiveLavaSources", 0);
        int scaled = 40 + (int) Math.round(progress * 400);
        if (configuredCap > 0) {
            scaled = Math.min(scaled, configuredCap);
        }
        return Math.min(600, scaled);
    }

    private void spawnTrench(Arena arena, TerrainPhaseConfig config, int volcanoRadius) {
        double footRadius = Math.max(volcanoRadius, VolcanoCaldera.NO_TREE_RADIUS);
        int offsetMin = config.getInt("lavaTrenchSpawnOffsetMin", 1);
        int offsetMax = Math.max(offsetMin, config.getInt("lavaTrenchSpawnOffsetMax", 5));
        double angle = random.nextDouble() * Math.PI * 2;
        double dist = footRadius + offsetMin + random.nextDouble() * (offsetMax - offsetMin);

        Trench trench = new Trench();
        trench.tipX = (int) Math.round(cx + Math.cos(angle) * dist);
        trench.tipZ = (int) Math.round(cz + Math.sin(angle) * dist);
        trench.heading = angle;
        trenches.add(trench);
    }

    private void extendTrench(World world, Arena arena, Trench trench, int budget, int depth) {
        if (trench.finished) {
            return;
        }
        for (int i = 0; i < budget && !trench.finished; i++) {
            double distBefore = CircleUtil.distanceFromCenter(cx, cz, trench.tipX, trench.tipZ);
            double outwardAngle = Math.atan2(trench.tipZ - cz, trench.tipX - cx);
            int[] next = pickOutwardStep(trench, distBefore, outwardAngle);
            if (next == null) {
                trench.finished = true;
                break;
            }
            int nx = next[0];
            int nz = next[1];

            if (!CircleUtil.isInsideCircle(cx, cz, nx, nz, arena.getRadius() - 6)) {
                trench.finished = true;
                break;
            }
            if (!carveTrenchCell(world, arena, trench, nx, nz, depth)) {
                trench.finished = true;
                break;
            }
            trench.tipX = nx;
            trench.tipZ = nz;
        }
    }

    /** Picks a step that moves away from the volcano center. */
    private int[] pickOutwardStep(Trench trench, double distBefore, double outwardAngle) {
        double wander = (random.nextDouble() - 0.5) * 0.5;
        double[] angles = {outwardAngle + wander, outwardAngle, outwardAngle - wander,
                outwardAngle + 0.35, outwardAngle - 0.35};

        int x = trench.tipX;
        int z = trench.tipZ;
        int bestX = x;
        int bestZ = z;
        double bestDist = distBefore;

        for (double angle : angles) {
            int nx = x + (int) Math.round(Math.cos(angle));
            int nz = z + (int) Math.round(Math.sin(angle));
            if (nx == x && nz == z) {
                continue;
            }
            double dist = CircleUtil.distanceFromCenter(cx, cz, nx, nz);
            if (dist > bestDist) {
                bestDist = dist;
                bestX = nx;
                bestZ = nz;
            }
        }

        if (bestX == x && bestZ == z) {
            return null;
        }
        trench.heading = Math.atan2(bestZ - z, bestX - x);
        return new int[]{bestX, bestZ};
    }

    private boolean carveTrenchCell(World world, Arena arena, Trench trench, int x, int z, int depth) {
        int perpX = (int) Math.round(-Math.sin(trench.heading));
        int perpZ = (int) Math.round(Math.cos(trench.heading));
        boolean carved = carveColumn(world, arena, trench, x, z, depth, true);
        if (perpX != 0 || perpZ != 0) {
            carveColumn(world, arena, trench, x + perpX, z + perpZ, depth, false);
        }
        return carved;
    }

    private boolean carveColumn(World world, Arena arena, Trench trench, int x, int z, int depth,
                                boolean recordFloor) {
        int terrainY = resolveTerrainGroundY(world, x, z);
        if (terrainY <= 2) {
            return false;
        }

        int groundY = terrainY;
        if (trench.lastGroundY > 0) {
            // Follow slopes gradually instead of snapping to every bump or tree trunk.
            groundY = Math.max(trench.lastGroundY - 1, Math.min(trench.lastGroundY + 1, terrainY));
        }
        trench.lastGroundY = groundY;

        clearVegetationAbove(world, x, z, groundY);

        for (int d = 0; d < depth; d++) {
            int y = groundY - d;
            if (isProtected(world, arena, x, y, z)) {
                return false;
            }
            Block block = world.getBlockAt(x, y, z);
            Material type = block.getType();
            if (type == Material.LAVA || type.isAir()) {
                continue; // crossing another trench
            }
            if (!CARVEABLE.contains(type)) {
                return false;
            }
            block.setType(Material.AIR, false);
        }

        if (recordFloor) {
            trench.floor.add(new BlockVector(x, groundY - depth + 1, z));
        }
        return true;
    }

    private void fillTrench(World world, Trench trench, int budget, int lavaLag, int maxActiveSources) {
        // Lava trails the digging front so trenches appear dry first, then flood.
        int fillLimit = trench.finished
                ? trench.floor.size()
                : Math.max(0, trench.floor.size() - Math.max(0, lavaLag));
        int filled = 0;
        while (trench.fillIndex < fillLimit && filled < budget) {
            BlockVector pos = trench.floor.get(trench.fillIndex);
            trench.fillIndex++;
            Block block = world.getBlockAt(pos.getBlockX(), pos.getBlockY(), pos.getBlockZ());
            if (!block.getType().isAir() && block.getType() != Material.LAVA) {
                continue;
            }
            // A source every third cell; flowing lava bridges the cells between.
            if (trench.fillIndex % 3 == 0 && trackedSources.size() < maxActiveSources) {
                placeLavaSource(block);
            } else {
                placeFlowingLava(block);
            }
            filled++;
        }
    }

    private void pruneExcessSources(World world, Arena arena, int maxActiveSources) {
        if (trackedSources.size() <= maxActiveSources) {
            return;
        }

        List<BlockVector> sorted = new ArrayList<>(trackedSources);
        sorted.sort(Comparator.comparingDouble(
                pos -> CircleUtil.distanceFromCenter(cx, cz, pos.getBlockX(), pos.getBlockZ())));
        int toRemove = trackedSources.size() - maxActiveSources;
        for (int i = 0; i < toRemove && i < sorted.size(); i++) {
            BlockVector pos = sorted.get(i);
            Block block = world.getBlockAt(pos.getBlockX(), pos.getBlockY(), pos.getBlockZ());
            if (isLavaSource(block)) {
                block.setType(Material.AIR, false);
            }
            trackedSources.remove(pos);
        }
    }

    private void validateTrackedSources(World world) {
        trackedSources.removeIf(pos ->
                !isLavaSource(world.getBlockAt(pos.getBlockX(), pos.getBlockY(), pos.getBlockZ())));
    }

    private boolean isLavaSource(Block block) {
        if (block.getType() != Material.LAVA) {
            return false;
        }
        BlockData data = block.getBlockData();
        if (data instanceof Levelled levelled) {
            return levelled.getLevel() == 0;
        }
        return true;
    }

    private void placeLavaSource(Block block) {
        BlockData data = Material.LAVA.createBlockData();
        if (data instanceof Levelled levelled) {
            levelled.setLevel(0);
            block.setBlockData(levelled, true);
        } else {
            block.setType(Material.LAVA, true);
        }
        trackedSources.add(new BlockVector(block.getX(), block.getY(), block.getZ()));
    }

    private void placeFlowingLava(Block block) {
        BlockData data = Material.LAVA.createBlockData();
        if (data instanceof Levelled levelled) {
            levelled.setLevel(1);
            block.setBlockData(levelled, true);
        } else {
            block.setType(Material.LAVA, true);
        }
    }

    private static int scaleFillRate(int base, double multiplier) {
        if (multiplier <= 1.0) {
            return base;
        }
        return Math.max(1, (int) Math.round(base * multiplier));
    }

    private static int scaleLag(int base, double multiplier) {
        if (multiplier <= 1.0) {
            return base;
        }
        return Math.max(0, (int) Math.round(base / multiplier));
    }

    private boolean isProtected(World world, Arena arena, int x, int y, int z) {
        Block block = world.getBlockAt(x, y, z);
        Material type = block.getType();
        if (type == Material.BEDROCK || type == Material.CHEST || type == Material.BARREL
                || type == Material.GOLD_BLOCK) {
            return true;
        }
        double dist = CircleUtil.distanceFromCenter(cx, cz, x, z);
        if (type == Material.OBSIDIAN) {
            int wallThickness = 4;
            return dist >= arena.getRadius() - 1 && dist <= arena.getRadius() + wallThickness + 1;
        }
        // Lava trenches erupt from the volcano foot; don't carve back through the cone.
        if (volcanoProtectRadius > 0 && dist <= volcanoProtectRadius + 2
                && (type == Material.NETHERRACK || type == Material.MAGMA_BLOCK || type == Material.BLACKSTONE)) {
            return true;
        }
        return false;
    }

    /** Finds the terrain surface, ignoring tree trunks, leaves, and other foliage. */
    private int resolveTerrainGroundY(World world, int x, int z) {
        for (int y = scanBaseY + 40; y >= 1; y--) {
            Material type = world.getBlockAt(x, y, z).getType();
            if (!type.isSolid() || type == Material.LAVA || isFoliage(type)) {
                continue;
            }
            return y;
        }
        return scanBaseY;
    }

    private boolean isFoliage(Material type) {
        return Tag.LOGS.isTagged(type) || Tag.LEAVES.isTagged(type)
                || type == Material.TALL_GRASS || type == Material.SHORT_GRASS
                || type == Material.DEAD_BUSH || type == Material.FIRE;
    }

    private void clearVegetationAbove(World world, int x, int z, int groundY) {
        for (int y = groundY + 1; y <= groundY + 16; y++) {
            Block above = world.getBlockAt(x, y, z);
            Material type = above.getType();
            if (type.isAir()) {
                break;
            }
            if (isFoliage(type) || CARVEABLE.contains(type)) {
                above.setType(Material.AIR, false);
            } else {
                break;
            }
        }
    }

    private static final class Trench {
        private double heading;
        private int tipX;
        private int tipZ;
        private int lastGroundY;
        private final List<BlockVector> floor = new ArrayList<>();
        private int fillIndex;
        private boolean finished;
    }
}
