package com.hungergames.arena;

import com.hungergames.gamemode.GameMode;
import com.hungergames.gamemode.PondConfig;
import com.hungergames.util.BlockBatchUtil;
import com.hungergames.util.CircleUtil;
import com.hungergames.util.NoiseUtil;
import com.hungergames.util.SpawnLocationUtil;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Levelled;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Carves shallow forest ponds and occasional small lakes into the volcano
 * arena during generation. Sites are deterministic from the terrain seed.
 */
public final class VolcanoPonds {

    private static final long SITE_SALT = 0x90AD51L;

    public record Pond(
            int x,
            int z,
            double radius,
            int depth,
            boolean lake
    ) {
        public boolean contains(double px, double pz, double extra) {
            double dx = px - x;
            double dz = pz - z;
            double r = radius + extra;
            return dx * dx + dz * dz <= r * r;
        }
    }

    private VolcanoPonds() {
    }

    public static boolean isActive(Arena arena, GameMode gameMode) {
        if (arena == null || gameMode == null
                || !"volcano".equalsIgnoreCase(gameMode.getInitialTerrain())) {
            return false;
        }
        PondConfig config = gameMode.getPonds();
        return config != null && config.isEnabled();
    }

    public static List<Pond> plan(
            Arena arena,
            GameMode gameMode,
            double[] groveArea,
            double[] settlementSites,
            int[][] heightmap,
            int minX,
            int minZ
    ) {
        if (!isActive(arena, gameMode)) {
            return List.of();
        }
        PondConfig config = gameMode.getPonds();
        Random random = new Random(arena.getTerrainSeed() * 31L + SITE_SALT);
        double scale = gameMode.getForestRadius() / 100.0;
        int pondTarget = Math.max(1, (int) Math.round(scale * config.getPondsPer100Radius()));
        int lakeTarget = Math.max(0, (int) Math.round(scale * config.getLakesPer100Radius()));
        List<Pond> ponds = new ArrayList<>();
        placeSites(ponds, arena, gameMode, config, groveArea, settlementSites, heightmap, minX, minZ,
                lakeTarget, true, random);
        placeSites(ponds, arena, gameMode, config, groveArea, settlementSites, heightmap, minX, minZ,
                pondTarget, false, random);
        return ponds;
    }

    public static boolean isInside(List<Pond> ponds, double x, double z, double extra) {
        if (ponds == null) {
            return false;
        }
        for (Pond pond : ponds) {
            if (pond.contains(x, z, extra)) {
                return true;
            }
        }
        return false;
    }

    public static void add(
            List<BlockBatchUtil.BlockChange> changes,
            Arena arena,
            GameMode gameMode,
            int[][] heightmap,
            int minX,
            int minZ,
            List<Pond> ponds
    ) {
        if (!isActive(arena, gameMode) || ponds == null || ponds.isEmpty()) {
            return;
        }
        Random random = new Random(arena.getTerrainSeed() * 31L + SITE_SALT + 17);
        for (Pond pond : ponds) {
            carvePond(changes, heightmap, minX, minZ, pond, random);
        }
    }

    private static void placeSites(
            List<Pond> ponds,
            Arena arena,
            GameMode gameMode,
            PondConfig config,
            double[] groveArea,
            double[] settlementSites,
            int[][] heightmap,
            int minX,
            int minZ,
            int target,
            boolean lake,
            Random random
    ) {
        int attempts = Math.max(8, target * 32);
        int placed = 0;
        for (int i = 0; i < attempts && placed < target; i++) {
            int minR = lake ? config.getLakeMinRadius() : config.getPondMinRadius();
            int maxR = lake ? config.getLakeMaxRadius() : config.getPondMaxRadius();
            int radius = minR + random.nextInt(Math.max(1, maxR - minR + 1));
            int depth = lake ? config.getLakeDepth() : config.getPondDepth();
            Pond candidate = pickSite(arena, gameMode, config, groveArea, settlementSites, ponds,
                    heightmap, minX, minZ, radius, depth, lake, random);
            if (candidate != null) {
                ponds.add(candidate);
                placed++;
            }
        }
    }

    private static Pond pickSite(
            Arena arena,
            GameMode gameMode,
            PondConfig config,
            double[] groveArea,
            double[] settlementSites,
            List<Pond> existing,
            int[][] heightmap,
            int minX,
            int minZ,
            int radius,
            int depth,
            boolean lake,
            Random random
    ) {
        double cx = arena.getCenterX();
        double cz = arena.getCenterZ();
        double arenaR = gameMode.getForestRadius();
        double minDist = VolcanoCaldera.NO_TREE_RADIUS + config.getCalderaMargin() + radius;
        double maxDist = arenaR - config.getWallMargin() - radius;
        if (maxDist <= minDist) {
            return null;
        }
        double dist = minDist + random.nextDouble() * (maxDist - minDist);
        double angle = random.nextDouble() * Math.PI * 2.0;
        int x = (int) Math.round(cx + Math.cos(angle) * dist);
        int z = (int) Math.round(cz + Math.sin(angle) * dist);
        if (!CircleUtil.isInsideCircle(cx, cz, x, z, arenaR - config.getWallMargin() - radius)) {
            return null;
        }
        if (Math.abs(dist - arenaR * 0.8) < radius + config.getSpawnRingMargin()) {
            return null;
        }
        if (groveArea != null && CircleUtil.isInsideCircle(
                groveArea[0], groveArea[1], x, z, groveArea[2] + radius + 6)) {
            return null;
        }
        if (nearSettlement(settlementSites, x, z, radius + 18)) {
            return null;
        }
        int minSep = config.getMinSeparation() + radius;
        for (Pond other : existing) {
            double dx = x - other.x();
            double dz = z - other.z();
            double need = minSep + other.radius();
            if (dx * dx + dz * dz < need * need) {
                return null;
            }
        }
        int scan = radius + 1;
        int minH = Integer.MAX_VALUE;
        int maxH = Integer.MIN_VALUE;
        for (int dx = -scan; dx <= scan; dx++) {
            for (int dz = -scan; dz <= scan; dz++) {
                if (dx * dx + dz * dz > radius * radius) {
                    continue;
                }
                int px = x + dx;
                int pz = z + dz;
                if (SpawnLocationUtil.isOnSpawnRing(px, pz, cx, cz, gameMode.getForestRadius())) {
                    return null;
                }
                int h = TerrainHeightmap.getHeight(heightmap, minX, minZ, px, pz);
                if (h <= 0) {
                    return null;
                }
                minH = Math.min(minH, h);
                maxH = Math.max(maxH, h);
            }
        }
        int slopeAllowance = lake ? config.getMaxSlope() + 1 : config.getMaxSlope();
        if (minH == Integer.MAX_VALUE || maxH - minH > slopeAllowance) {
            return null;
        }
        return new Pond(x, z, radius, depth, lake);
    }

    private static boolean nearSettlement(double[] sites, int x, int z, double extra) {
        if (sites == null) {
            return false;
        }
        double extraSq = extra * extra;
        for (int i = 0; i + 1 < sites.length; i += 2) {
            double dx = x - sites[i];
            double dz = z - sites[i + 1];
            if (dx * dx + dz * dz <= extraSq) {
                return true;
            }
        }
        return false;
    }

    private static void carvePond(
            List<BlockBatchUtil.BlockChange> changes,
            int[][] heights,
            int minX,
            int minZ,
            Pond pond,
            Random random
    ) {
        int r = (int) Math.ceil(pond.radius()) + 2;
        int waterY = Integer.MAX_VALUE;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (dx * dx + dz * dz > pond.radius() * pond.radius()) {
                    continue;
                }
                int h = TerrainHeightmap.getHeight(heights, minX, minZ, pond.x() + dx, pond.z() + dz);
                if (h > 0) {
                    waterY = Math.min(waterY, h);
                }
            }
        }
        if (waterY == Integer.MAX_VALUE) {
            return;
        }
        int seed = pond.x() * 734287 + pond.z() * 912371;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                double warpX = NoiseUtil.continuousNoise2D((pond.x() + dx) * 0.11, (pond.z() + dz) * 0.11, seed);
                double warpZ = NoiseUtil.continuousNoise2D((pond.x() + dx) * 0.11, (pond.z() + dz) * 0.11, seed + 19);
                double wx = pond.x() + dx + warpX * pond.radius() * 0.22;
                double wz = pond.z() + dz + warpZ * pond.radius() * 0.22;
                double dist = Math.hypot(wx - pond.x(), wz - pond.z());
                double edgeNoise = NoiseUtil.continuousNoise2D(wx * 0.18, wz * 0.18, seed + 41);
                double edge = pond.radius() * (0.88 + edgeNoise * 0.14);
                if (dist > edge + 1.6) {
                    continue;
                }
                int x = pond.x() + dx;
                int z = pond.z() + dz;
                int surfaceY = TerrainHeightmap.getHeight(heights, minX, minZ, x, z);
                if (surfaceY <= 0) {
                    continue;
                }
                double norm = dist / Math.max(1.0, edge);
                boolean water = dist <= edge;
                int localDepth = water
                        ? 1 + (int) Math.round((1.0 - Math.min(1.0, norm)) * (pond.depth() - 1))
                        : 0;
                int floorY = waterY - localDepth;
                int airTop = Math.max(surfaceY + 4, waterY + 3);
                Material shore = shoreMaterial(random);
                for (int y = floorY; y <= airTop; y++) {
                    if (y == floorY) {
                        changes.add(new BlockBatchUtil.BlockChange(x, y, z, floorMaterial(norm, random)));
                    } else if (water && y <= waterY) {
                        changes.add(new BlockBatchUtil.BlockChange(x, y, z, Material.WATER, createWaterSourceData()));
                    } else if (!water && y == surfaceY && dist <= edge + 1.6) {
                        changes.add(new BlockBatchUtil.BlockChange(x, y, z, shore));
                    } else if (y > (water ? waterY : surfaceY)) {
                        changes.add(new BlockBatchUtil.BlockChange(x, y, z, Material.AIR));
                    }
                }
                if (water && random.nextDouble() < (pond.lake() ? 0.10 : 0.06)) {
                    changes.add(new BlockBatchUtil.BlockChange(x, waterY + 1, z, Material.LILY_PAD));
                }
                if (!water && shore == Material.SAND && dist <= edge + 1.4 && random.nextDouble() < 0.10) {
                    int cane = 1 + random.nextInt(3);
                    for (int i = 1; i <= cane; i++) {
                        changes.add(new BlockBatchUtil.BlockChange(x, surfaceY + i, z, Material.SUGAR_CANE));
                    }
                }
            }
        }
    }

    private static Material floorMaterial(double norm, Random random) {
        if (norm < 0.35) {
            return random.nextDouble() < 0.55 ? Material.CLAY : Material.SAND;
        }
        if (norm < 0.7) {
            return random.nextDouble() < 0.5 ? Material.SAND : Material.DIRT;
        }
        return random.nextDouble() < 0.65 ? Material.SAND : Material.GRASS_BLOCK;
    }

    private static Material shoreMaterial(Random random) {
        double roll = random.nextDouble();
        if (roll < 0.55) {
            return Material.SAND;
        }
        if (roll < 0.8) {
            return Material.GRASS_BLOCK;
        }
        return Material.DIRT;
    }

    private static BlockData createWaterSourceData() {
        BlockData data = Material.WATER.createBlockData();
        if (data instanceof Levelled levelled) {
            levelled.setLevel(0);
        }
        return data;
    }
}
