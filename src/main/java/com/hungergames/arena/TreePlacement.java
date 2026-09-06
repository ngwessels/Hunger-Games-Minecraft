package com.hungergames.arena;

import com.hungergames.gamemode.TreePlacementConfig;
import com.hungergames.util.BlockBatchUtil;
import com.hungergames.util.CircleUtil;
import com.hungergames.util.NoiseUtil;
import com.hungergames.util.SpawnLocationUtil;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Leaves;

import java.util.List;

public final class TreePlacement {

    private TreePlacement() {
    }

    public static boolean shouldPlaceTree(int x, int z, double cx, double cz, int arenaRadius, int seed,
                                          Material surface, TreePlacementConfig config) {
        if (SpawnLocationUtil.isOnSpawnRing(x, z, cx, cz, arenaRadius)) {
            return false;
        }

        if (config.getExcludeCenterRadius() > 0
                && CircleUtil.isInsideCircle(cx, cz, x, z, config.getExcludeCenterRadius())) {
            return false;
        }

        boolean fertile = surface == Material.GRASS_BLOCK || surface == Material.PODZOL;
        if (!fertile && surface != Material.COARSE_DIRT) {
            return false;
        }

        int cellSize = Math.max(3, config.getCellSize());
        int cellX = Math.floorDiv(x, cellSize);
        int cellZ = Math.floorDiv(z, cellSize);
        int hash = cellHash(cellX, cellZ, seed);
        int anchorX = cellX * cellSize + 1 + hash % 3;
        int anchorZ = cellZ * cellSize + 1 + (hash / 3) % 3;
        if (x != anchorX || z != anchorZ) {
            return false;
        }

        double cluster = NoiseUtil.continuousFractalNoise2D(x * 0.062, z * 0.062, seed + 99, 4);
        if (cluster < config.getClusterThreshold()) {
            return false;
        }

        double density = Math.min(1.0, (cluster - config.getClusterThreshold())
                / Math.max(0.001, config.getFullDensityCluster() - config.getClusterThreshold()));
        double chance = config.getMinDensityChance()
                + (config.getMaxDensityChance() - config.getMinDensityChance()) * density;
        if (!fertile) {
            chance *= config.getInfertileSoilChanceScale();
        }
        return (hash / 9) % 256 < (int) (chance * 256);
    }

    public static void addTree(List<BlockBatchUtil.BlockChange> changes, int x, int baseY, int z,
                               int seed, TreePlacementConfig config) {
        TreePlacementConfig.TreeSpecies species = pickSpecies(x, z, seed, config);
        Material log = species.log();
        Material leaves = species.leaves();

        int heightRange = Math.max(1, config.getMaxHeight() - config.getMinHeight());
        double noise = Math.abs(NoiseUtil.continuousNoise2D(x * 0.2, z * 0.2, seed + 42));
        int height = config.getMinHeight() + (int) (noise * heightRange);

        for (int dy = 0; dy < height; dy++) {
            changes.add(new BlockBatchUtil.BlockChange(x, baseY + dy, z, log));
        }

        int canopyRadius = log == Material.SPRUCE_LOG ? 2 : 2;
        int canopyHeight = log == Material.SPRUCE_LOG ? 3 : 2;
        for (int dx = -canopyRadius; dx <= canopyRadius; dx++) {
            for (int dz = -canopyRadius; dz <= canopyRadius; dz++) {
                for (int dy = 0; dy <= canopyHeight; dy++) {
                    if (Math.abs(dx) + Math.abs(dz) + dy > canopyRadius + 2) {
                        continue;
                    }
                    if (dx == 0 && dz == 0 && dy == 0) {
                        continue;
                    }
                    changes.add(new BlockBatchUtil.BlockChange(
                            x + dx, baseY + height + dy, z + dz, leaves, persistentLeaves(leaves)));
                }
            }
        }
    }

    public static BlockData persistentLeaves(Material leavesMaterial) {
        BlockData data = leavesMaterial.createBlockData();
        if (data instanceof Leaves leafData) {
            leafData.setPersistent(true);
            return leafData;
        }
        return data;
    }

    private static TreePlacementConfig.TreeSpecies pickSpecies(int x, int z, int seed,
                                                                 TreePlacementConfig config) {
        List<TreePlacementConfig.TreeSpecies> pool = config.getSpecies();
        if (pool.isEmpty()) {
            return new TreePlacementConfig.TreeSpecies(Material.OAK_LOG, Material.OAK_LEAVES, 1.0);
        }
        if (pool.size() == 1) {
            return pool.getFirst();
        }
        double pick = NoiseUtil.continuousNoise2D(x * 0.09, z * 0.09, seed + 300);
        if (pool.size() == 2) {
            return pick > 0 ? pool.get(1) : pool.get(0);
        }
        if (pick > 0.35) {
            return pool.get(Math.min(1, pool.size() - 1));
        }
        if (pick < -0.25) {
            return pool.get(Math.min(2, pool.size() - 1));
        }
        return pool.getFirst();
    }

    private static int cellHash(int cellX, int cellZ, int seed) {
        int h = cellX * 734287 ^ cellZ * 912271 ^ seed;
        h = (h ^ (h >>> 16)) * 0x45d9f3b;
        h ^= h >>> 13;
        return h & 0x7fffffff;
    }
}
