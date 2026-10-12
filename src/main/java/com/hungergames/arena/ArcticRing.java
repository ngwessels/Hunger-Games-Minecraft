package com.hungergames.arena;

import com.hungergames.gamemode.ArcticConfig;
import com.hungergames.gamemode.GameMode;
import com.hungergames.util.BlockBatchUtil;
import com.hungergames.util.CircleUtil;
import com.hungergames.util.NoiseUtil;
import com.hungergames.util.SpawnLocationUtil;
import org.bukkit.Material;
import org.bukkit.block.Biome;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Levelled;

import java.util.List;
import java.util.Random;

/**
 * Frozen terrain on volcano arenas: snow plains, sea ice, glaciers, and
 * polar-bear sites. Default is an outer ring beyond the forest radius;
 * {@code iceWorldStart} ices the whole disc except the caldera.
 */
public final class ArcticRing {

    private static final long SITE_SALT = 0xA4C71C3L;
    private static final long BUILD_SALT = 0x1CE51C3L;

    private ArcticRing() {
    }

    public static boolean isActive(Arena arena, GameMode gameMode) {
        if (arena == null || gameMode == null) {
            return false;
        }
        ArcticConfig config = gameMode.getArctic();
        if (config == null || !config.isEnabled()) {
            return false;
        }
        if (config.isIceWorldStart()) {
            return true;
        }
        return gameMode.getArenaRadius() > gameMode.getForestRadius();
    }

    public static boolean isArcticColumn(Arena arena, GameMode gameMode, int x, int z) {
        return blendFactor(arena, gameMode, x, z) >= 1;
    }

    /** 0 inside the forest/caldera, 1 fully arctic, ramp across the blend band. */
    public static double blendFactor(Arena arena, GameMode gameMode, int x, int z) {
        if (!isActive(arena, gameMode)) {
            return 0;
        }
        ArcticConfig config = gameMode.getArctic();
        int blend = config.getBlendWidth();
        double dist = CircleUtil.distanceFromCenter(arena.getCenterX(), arena.getCenterZ(), x, z);
        int inner;
        if (config.isIceWorldStart()) {
            inner = VolcanoCaldera.NO_TREE_RADIUS;
        } else {
            inner = gameMode.getForestRadius();
        }
        if (dist <= inner - blend) {
            return 0;
        }
        if (dist >= inner) {
            return 1;
        }
        return (dist - (inner - blend)) / blend;
    }

    public static void refreshLayout(Arena arena, GameMode gameMode) {
        arena.getArcticLayout().clear();
        if (!isActive(arena, gameMode)) {
            return;
        }
        int radius = arena.getRadius();
        double cx = arena.getCenterX();
        double cz = arena.getCenterZ();
        int minX = (int) (cx - radius);
        int maxX = (int) (cx + radius);
        int minZ = (int) (cz - radius);
        int maxZ = (int) (cz + radius);
        int[][] heightmap = TerrainHeightmap.generate(cx, cz, radius, minX, maxX, minZ, maxZ,
                (int) arena.getCenterY(), arena.getTerrainSeed(), gameMode.getInitialTerrain(),
                gameMode.getForestRadius(), gameMode.getArctic().getBlendWidth());
        planSites(arena, gameMode, heightmap, minX, minZ, arena.getArcticLayout());
    }

    public static void add(List<BlockBatchUtil.BlockChange> changes, Arena arena, GameMode gameMode,
                           int[][] heightmap, int minX, int minZ) {
        if (!isActive(arena, gameMode)) {
            arena.getArcticLayout().clear();
            return;
        }
        ArcticLayout layout = arena.getArcticLayout();
        planSites(arena, gameMode, heightmap, minX, minZ, layout);
        Random random = new Random(arena.getTerrainSeed() * 31L + BUILD_SALT);
        overlaySurface(changes, arena, gameMode, heightmap, minX, minZ);
        addFrozenLakes(changes, arena, gameMode, heightmap, minX, minZ, random);
        for (ArcticLayout.Site glacier : layout.getGlacierSites()) {
            buildGlacier(changes, glacier, random);
        }
        addIceSpikes(changes, arena, gameMode, heightmap, minX, minZ, layout, random);
    }

    public static Material surfaceMaterial(Arena arena, GameMode gameMode, int x, int z, int seed,
                                           Material forestSurface) {
        double blend = blendFactor(arena, gameMode, x, z);
        if (blend <= 0) {
            return forestSurface;
        }
        Material arctic = arcticSurfaceAt(x, z, seed);
        if (blend >= 1) {
            return arctic;
        }
        double n = NoiseUtil.continuousNoise2D(x * 0.19, z * 0.19, seed + 311);
        return n < blend * 2 - 0.35 ? arctic : forestSurface;
    }

    public static Material subsurfaceMaterial(Arena arena, GameMode gameMode, int x, int y, int z,
                                              int surfaceY, int seed, Material forestSub) {
        double blend = blendFactor(arena, gameMode, x, z);
        if (blend < 0.55) {
            return forestSub;
        }
        int depth = surfaceY - y;
        if (depth <= 2) {
            return Material.SNOW_BLOCK;
        }
        if (depth <= 5) {
            return NoiseUtil.noise2D(x, z, seed + y) > 0.1 ? Material.PACKED_ICE : Material.SNOW_BLOCK;
        }
        return Material.STONE;
    }

    public static Biome biomeAt(Arena arena, GameMode gameMode, int x, int z) {
        if (!isArcticColumn(arena, gameMode, x, z)) {
            return null;
        }
        if (nearGlacier(arena, x, z, 18)) {
            return Biome.ICE_SPIKES;
        }
        double n = NoiseUtil.continuousNoise2D(x * 0.04, z * 0.04, arena.getTerrainSeed() + 77);
        return n > 0.28 ? Biome.FROZEN_OCEAN : Biome.SNOWY_PLAINS;
    }

    private static Material arcticSurfaceAt(int x, int z, int seed) {
        double n = NoiseUtil.continuousFractalNoise2D(x * 0.06, z * 0.06, seed + 401, 3);
        if (n > 0.38) {
            return Material.PACKED_ICE;
        }
        if (n > 0.08) {
            return Material.SNOW_BLOCK;
        }
        return Material.ICE;
    }

    private static void overlaySurface(List<BlockBatchUtil.BlockChange> changes, Arena arena, GameMode gameMode,
                                       int[][] heightmap, int minX, int minZ) {
        int radius = arena.getRadius();
        double cx = arena.getCenterX();
        double cz = arena.getCenterZ();
        int seed = arena.getTerrainSeed();
        int minWorldX = (int) (cx - radius);
        int maxWorldX = (int) (cx + radius);
        int minWorldZ = (int) (cz - radius);
        int maxWorldZ = (int) (cz + radius);
        for (int x = minWorldX; x <= maxWorldX; x++) {
            for (int z = minWorldZ; z <= maxWorldZ; z++) {
                if (!CircleUtil.isInsideCircle(cx, cz, x, z, radius)) {
                    continue;
                }
                double blend = blendFactor(arena, gameMode, x, z);
                if (blend < 1) {
                    continue;
                }
                int surfaceY = TerrainHeightmap.getHeight(heightmap, minX, minZ, x, z);
                Material surface = arcticSurfaceAt(x, z, seed);
                changes.add(new BlockBatchUtil.BlockChange(x, surfaceY, z, surface));
                if (surface == Material.SNOW_BLOCK
                        && NoiseUtil.continuousNoise2D(x * 0.27, z * 0.27, seed + 55) > 0.15) {
                    changes.add(new BlockBatchUtil.BlockChange(x, surfaceY + 1, z, Material.SNOW));
                }
            }
        }
    }

    private static void planSites(Arena arena, GameMode gameMode, int[][] heightmap,
                                  int minX, int minZ, ArcticLayout layout) {
        layout.clear();
        ArcticConfig config = gameMode.getArctic();
        Random random = new Random(arena.getTerrainSeed() * 31L + SITE_SALT);
        double cx = arena.getCenterX();
        double cz = arena.getCenterZ();
        int forest = gameMode.getForestRadius();
        int radius = arena.getRadius();
        double ringInner = forest + 14;
        double ringOuter = radius - 10;
        if (ringOuter <= ringInner) {
            return;
        }

        int glacierCount = config.getGlacierCount();
        double glacierDist = ringInner + (ringOuter - ringInner) * 0.55;
        double baseAngle = random.nextDouble() * Math.PI * 2;
        for (int i = 0; i < glacierCount; i++) {
            double angle = baseAngle + i * (Math.PI * 2 / Math.max(1, glacierCount));
            angle += (random.nextDouble() - 0.5) * 0.22;
            int x = (int) Math.round(cx + Math.cos(angle) * glacierDist);
            int z = (int) Math.round(cz + Math.sin(angle) * glacierDist);
            if (!CircleUtil.isInsideCircle(cx, cz, x, z, radius - 8)
                    || CircleUtil.isInsideCircle(cx, cz, x, z, forest + 8)) {
                continue;
            }
            int y = TerrainHeightmap.getHeight(heightmap, minX, minZ, x, z);
            layout.addGlacier(x, y, z);
        }

        int bearCount = config.getPolarBearCount();
        double bearDist = ringInner + (ringOuter - ringInner) * 0.42;
        double bearBase = baseAngle + Math.PI / Math.max(1, bearCount);
        for (int i = 0; i < bearCount; i++) {
            double angle = bearBase + i * (Math.PI * 2 / Math.max(1, bearCount));
            angle += (random.nextDouble() - 0.5) * 0.3;
            int x = (int) Math.round(cx + Math.cos(angle) * bearDist);
            int z = (int) Math.round(cz + Math.sin(angle) * bearDist);
            if (!CircleUtil.isInsideCircle(cx, cz, x, z, radius - 6)
                    || CircleUtil.isInsideCircle(cx, cz, x, z, forest + 6)
                    || SpawnLocationUtil.isOnSpawnRing(x, z, cx, cz, forest)
                    || nearGlacier(layout, x, z, 14)) {
                continue;
            }
            int y = TerrainHeightmap.getHeight(heightmap, minX, minZ, x, z) + 1;
            layout.addPolarBear(x, y, z);
        }
    }

    private static void addFrozenLakes(List<BlockBatchUtil.BlockChange> changes, Arena arena, GameMode gameMode,
                                       int[][] heightmap, int minX, int minZ, Random random) {
        double cx = arena.getCenterX();
        double cz = arena.getCenterZ();
        int forest = gameMode.getForestRadius();
        int radius = arena.getRadius();
        int lakes = 4;
        for (int i = 0; i < lakes; i++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double dist = forest + 18 + random.nextDouble() * Math.max(8, radius - forest - 28);
            int lx = (int) Math.round(cx + Math.cos(angle) * dist);
            int lz = (int) Math.round(cz + Math.sin(angle) * dist);
            if (!isArcticColumn(arena, gameMode, lx, lz) || nearGlacier(arena, lx, lz, 16)) {
                continue;
            }
            int lakeR = 6 + random.nextInt(6);
            carveFrozenLake(changes, heightmap, minX, minZ, lx, lz, lakeR, random);
        }
    }

    private static void carveFrozenLake(List<BlockBatchUtil.BlockChange> changes, int[][] heightmap,
                                        int minX, int minZ, int lx, int lz, int lakeR, Random random) {
        int waterY = Integer.MAX_VALUE;
        for (int dx = -lakeR; dx <= lakeR; dx++) {
            for (int dz = -lakeR; dz <= lakeR; dz++) {
                if (dx * dx + dz * dz > lakeR * lakeR) {
                    continue;
                }
                int h = TerrainHeightmap.getHeight(heightmap, minX, minZ, lx + dx, lz + dz);
                if (h > 0) {
                    waterY = Math.min(waterY, h);
                }
            }
        }
        if (waterY == Integer.MAX_VALUE) {
            return;
        }
        BlockData water = createWaterSourceData();
        for (int dx = -lakeR; dx <= lakeR; dx++) {
            for (int dz = -lakeR; dz <= lakeR; dz++) {
                if (dx * dx + dz * dz > lakeR * lakeR) {
                    continue;
                }
                int x = lx + dx;
                int z = lz + dz;
                int surfaceY = TerrainHeightmap.getHeight(heightmap, minX, minZ, x, z);
                if (surfaceY <= 0) {
                    continue;
                }
                changes.add(new BlockBatchUtil.BlockChange(x, waterY - 1, z, Material.PACKED_ICE));
                changes.add(new BlockBatchUtil.BlockChange(x, waterY, z, Material.WATER, water));
                Material cap = random.nextDouble() < 0.22 ? Material.PACKED_ICE : Material.ICE;
                changes.add(new BlockBatchUtil.BlockChange(x, waterY + 1, z, cap));
                if (surfaceY > waterY + 1) {
                    for (int y = waterY + 2; y <= surfaceY + 2; y++) {
                        changes.add(new BlockBatchUtil.BlockChange(x, y, z, Material.AIR));
                    }
                }
            }
        }
    }

    private static void buildGlacier(List<BlockBatchUtil.BlockChange> changes, ArcticLayout.Site site,
                                     Random random) {
        int radius = 9 + random.nextInt(6);
        int height = 8 + random.nextInt(8);
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                double dist = Math.hypot(dx, dz);
                if (dist > radius + 0.4) {
                    continue;
                }
                double norm = dist / radius;
                int colH = (int) Math.round(height * (1.0 - norm * norm) * (0.75 + random.nextDouble() * 0.25));
                if (colH < 2) {
                    continue;
                }
                for (int dy = 0; dy < colH; dy++) {
                    Material mat;
                    if (norm < 0.35 && dy < colH - 2) {
                        mat = Material.BLUE_ICE;
                    } else if (random.nextDouble() < 0.18) {
                        mat = Material.BLUE_ICE;
                    } else {
                        mat = Material.PACKED_ICE;
                    }
                    changes.add(new BlockBatchUtil.BlockChange(site.x() + dx, site.y() + dy, site.z() + dz, mat));
                }
            }
        }
    }

    private static void addIceSpikes(List<BlockBatchUtil.BlockChange> changes, Arena arena, GameMode gameMode,
                                     int[][] heightmap, int minX, int minZ, ArcticLayout layout, Random random) {
        for (ArcticLayout.Site glacier : layout.getGlacierSites()) {
            int spikes = 2 + random.nextInt(3);
            for (int i = 0; i < spikes; i++) {
                int dx = random.nextInt(11) - 5;
                int dz = random.nextInt(11) - 5;
                int x = glacier.x() + dx;
                int z = glacier.z() + dz;
                if (!isArcticColumn(arena, gameMode, x, z)) {
                    continue;
                }
                int y = TerrainHeightmap.getHeight(heightmap, minX, minZ, x, z) + 1;
                int h = 4 + random.nextInt(7);
                for (int dy = 0; dy < h; dy++) {
                    Material mat = dy > h / 2 ? Material.PACKED_ICE : Material.BLUE_ICE;
                    changes.add(new BlockBatchUtil.BlockChange(x, y + dy, z, mat));
                    if (dy > 2 && random.nextDouble() < 0.35) {
                        changes.add(new BlockBatchUtil.BlockChange(x + 1, y + dy, z, Material.PACKED_ICE));
                    }
                }
            }
        }
    }

    private static boolean nearGlacier(Arena arena, int x, int z, int extra) {
        return nearGlacier(arena.getArcticLayout(), x, z, extra);
    }

    private static boolean nearGlacier(ArcticLayout layout, int x, int z, int extra) {
        int extraSq = extra * extra;
        for (ArcticLayout.Site site : layout.getGlacierSites()) {
            int dx = x - site.x();
            int dz = z - site.z();
            if (dx * dx + dz * dz <= extraSq) {
                return true;
            }
        }
        return false;
    }

    private static BlockData createWaterSourceData() {
        BlockData data = Material.WATER.createBlockData();
        if (data instanceof Levelled levelled) {
            levelled.setLevel(0);
        }
        return data;
    }
}
