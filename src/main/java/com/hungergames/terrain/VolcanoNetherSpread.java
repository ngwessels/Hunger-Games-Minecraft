package com.hungergames.terrain;

import com.hungergames.arena.Arena;
import com.hungergames.arena.VolcanoCaldera;
import com.hungergames.gamemode.GameMode;
import com.hungergames.gamemode.TerrainPhaseConfig;
import com.hungergames.util.CircleUtil;
import com.hungergames.util.NoiseUtil;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;

import java.util.Set;

/**
 * Spreads nether conversion outward from the caldera as the volcano grows:
 * overworld soil becomes netherrack/nylium, water becomes lava, and forest
 * trees become crimson or warped fungi. By the end of the match the full
 * arena is nether.
 */
public class VolcanoNetherSpread {

    private static final Set<Material> PROTECTED = Set.of(
            Material.BEDROCK, Material.CHEST, Material.TRAPPED_CHEST,
            Material.BARREL, Material.ENDER_CHEST, Material.GOLD_BLOCK, Material.BEACON,
            Material.SPAWNER, Material.IRON_BLOCK, Material.EMERALD_BLOCK,
            Material.DIAMOND_BLOCK, Material.NETHERITE_BLOCK
    );

    private final int[] cursor = new int[1];

    private double cx;
    private double cz;
    private int convertedRadius;
    private int scanBaseY;
    private int forestRadius;
    private int meltLag;
    private boolean arcticEnabled;

    public void initialize(double centerX, double centerZ, int surfaceY) {
        this.cx = centerX;
        this.cz = centerZ;
        this.scanBaseY = surfaceY;
        this.convertedRadius = VolcanoCaldera.NO_TREE_RADIUS;
        this.cursor[0] = 0;
    }

    public int getConvertedRadius() {
        return convertedRadius;
    }

    /** Allows ice recapture to pull the volcanic front back during biome war. */
    public void setConvertedRadius(int radius) {
        this.convertedRadius = Math.max(VolcanoCaldera.NO_TREE_RADIUS, radius);
    }

    /** Horizontal footprint of nether growth, with a noisy edge so the front is not a perfect circle. */
    public boolean contains(int x, int z) {
        double dist = CircleUtil.distanceFromCenter(cx, cz, x, z);
        double jitter = NoiseUtil.continuousNoise2D(x * 0.07, z * 0.07, 71) * 5.0;
        return dist <= convertedRadius + jitter;
    }

    /**
     * True when this column has already become nether ground (or sits inside the
     * original caldera). Used to gate nether vs overworld mob spawns.
     */
    public boolean isConvertedGround(World world, int x, int z) {
        if (CircleUtil.isInsideCircle(cx, cz, x, z, VolcanoCaldera.NO_TREE_RADIUS)) {
            return true;
        }
        if (!contains(x, z)) {
            return false;
        }
        int y = VolcanoHeatUtil.findTerrainGroundY(world, x, z, scanBaseY);
        return isNetherGround(world.getBlockAt(x, y, z).getType());
    }

    public void tick(World world, Arena arena, GameMode gameMode, TerrainPhaseConfig config, int targetRadius) {
        convertedRadius = Math.max(convertedRadius, Math.max(VolcanoCaldera.NO_TREE_RADIUS, targetRadius));
        forestRadius = gameMode != null ? gameMode.getForestRadius() : arena.getRadius();
        meltLag = Math.max(1, config.getInt("arcticMeltLagBlocks", 10));
        arcticEnabled = gameMode != null && gameMode.getArctic() != null && gameMode.getArctic().isEnabled()
                && forestRadius < arena.getRadius();
        int scanRadius = Math.min(arena.getRadius() - 2, convertedRadius + 6);
        int columns = Math.max(8, config.getInt("netherSpreadColumnsPerTick", 48));
        double radiusScale = config.getDouble("netherSpreadBudgetRadiusScale", 0.04);
        int budget = (int) Math.round(columns * (1.0 + scanRadius * radiusScale));
        int depth = Math.max(2, config.getInt("netherSpreadScanDepth", 8));
        int height = Math.max(8, config.getInt("netherSpreadScanHeightAbove", 40));

        int cxBlock = (int) cx;
        int czBlock = (int) cz;
        int radiusSq = scanRadius * scanRadius;
        int side = 2 * scanRadius + 1;
        int totalCells = side * side;
        if (totalCells <= 0) {
            return;
        }

        int converted = 0;
        int startCursor = cursor[0];
        while (converted < budget) {
            int idx = cursor[0] % totalCells;
            cursor[0]++;
            if (cursor[0] - startCursor > totalCells) {
                break;
            }

            int dx = idx % side - scanRadius;
            int dz = idx / side - scanRadius;
            int distSq = dx * dx + dz * dz;
            if (distSq > radiusSq) {
                continue;
            }
            int innerSkip = Math.max(0, convertedRadius - 8);
            int x = cxBlock + dx;
            int z = czBlock + dz;
            if (innerSkip > 0 && distSq < innerSkip * innerSkip) {
                if (!shouldRescanInnerColumn(world, x, z)) {
                    continue;
                }
            }
            if (!contains(x, z)) {
                continue;
            }
            if (!CircleUtil.isInsideCircle(cx, cz, x, z, arena.getRadius() - 2)) {
                continue;
            }
            boolean lavaStage = !isArcticColumn(x, z) || containsLava(x, z);
            if (convertColumn(world, x, z, depth, height, lavaStage)) {
                if (lavaStage) {
                    maybePlaceGlowstoneClump(world, arena, config, x, z);
                    maybePlaceNetherrackIsland(world, arena, config, x, z);
                }
                converted++;
            }
        }
    }

    private boolean shouldRescanInnerColumn(World world, int x, int z) {
        if (!arcticEnabled || !isArcticColumn(x, z)) {
            return false;
        }
        return containsLava(x, z) && !isConvertedGround(world, x, z);
    }

    private boolean isArcticColumn(int x, int z) {
        return arcticEnabled && CircleUtil.distanceFromCenter(cx, cz, x, z) >= forestRadius;
    }

    private boolean containsLava(int x, int z) {
        double dist = CircleUtil.distanceFromCenter(cx, cz, x, z);
        double jitter = NoiseUtil.continuousNoise2D(x * 0.07, z * 0.07, 71) * 5.0;
        return dist <= convertedRadius - meltLag + jitter;
    }

    private boolean convertColumn(World world, int x, int z, int depth, int height, boolean lavaStage) {
        int groundY = VolcanoHeatUtil.findTerrainGroundY(world, x, z, scanBaseY);
        int minY = Math.max(1, groundY - depth);
        int maxY = groundY + height;
        boolean crimson = isCrimsonColumn(x, z);
        boolean changed = false;

        for (int y = minY; y <= maxY; y++) {
            Block block = world.getBlockAt(x, y, z);
            Material current = block.getType();
            if (current.isAir() || current == Material.LAVA) {
                continue;
            }
            if (isProtected(block)) {
                continue;
            }
            boolean surface = y == groundY;
            Material replacement = lavaStage
                    ? transform(current, x, y, z, crimson, surface)
                    : meltArctic(current);
            if (replacement == null || replacement == current) {
                continue;
            }
            block.setType(replacement, false);
            changed = true;
        }

        if (lavaStage && (changed || !isNetherBiome(world, x, groundY, z))) {
            applyNetherBiome(world, x, groundY, z, crimson);
            changed = true;
        }
        return changed;
    }

    /**
     * One hanging glowstone blob per grid cell, placed in open air above converted
     * ground so the nether spread grows the same floating clumps as a nether cavern.
     */
    private void maybePlaceGlowstoneClump(World world, Arena arena, TerrainPhaseConfig config,
                                          int x, int z) {
        if (config.getInt("glowstoneClumpsEnabled", 1) == 0) {
            return;
        }
        int cellSize = Math.max(6, config.getInt("glowstoneClumpCellSize", 12));
        int cellX = Math.floorDiv(x, cellSize);
        int cellZ = Math.floorDiv(z, cellSize);
        int hash = cellHash(cellX, cellZ, arena.getTerrainSeed() + 211);
        int inset = Math.min(3, cellSize / 3);
        int span = Math.max(1, cellSize - inset * 2);
        int anchorX = cellX * cellSize + inset + hash % span;
        int anchorZ = cellZ * cellSize + inset + (hash / span) % span;
        if (x != anchorX || z != anchorZ) {
            return;
        }
        int chance = (int) Math.round(Math.max(0.0, Math.min(1.0,
                config.getDouble("glowstoneClumpChance", 0.34))) * 1000);
        if (hash % 1000 >= chance) {
            return;
        }
        if (!CircleUtil.isInsideCircle(cx, cz, x, z, arena.getRadius() - 4)) {
            return;
        }

        int groundY = VolcanoHeatUtil.findTerrainGroundY(world, x, z, scanBaseY);
        int canopyY = findCanopyTopY(world, x, z, groundY);
        int minHover = Math.max(4, config.getInt("glowstoneClumpMinHover", 6));
        int maxHover = Math.max(minHover, config.getInt("glowstoneClumpMaxHover", 15));
        int hover = minHover + hash % (maxHover - minHover + 1);
        int centerY = canopyY + hover;
        if (world.getBlockAt(x, centerY, z).getType() != Material.AIR) {
            return;
        }

        int minRadius = Math.max(2, config.getInt("glowstoneClumpMinRadius", 2));
        int maxRadius = Math.max(minRadius, config.getInt("glowstoneClumpMaxRadius", 4));
        int radius = minRadius + (hash / 17) % (maxRadius - minRadius + 1);
        placeGlowstoneBlob(world, x, centerY, z, radius, hash);
    }

    /**
     * One floating netherrack island per grid cell, high above converted ground,
     * using the same cell-hash placement as glowstone clumps.
     */
    private void maybePlaceNetherrackIsland(World world, Arena arena, TerrainPhaseConfig config,
                                            int x, int z) {
        if (config.getInt("netherIslandsEnabled", 1) == 0) {
            return;
        }
        int cellSize = Math.max(10, config.getInt("netherIslandCellSize", 20));
        int cellX = Math.floorDiv(x, cellSize);
        int cellZ = Math.floorDiv(z, cellSize);
        int hash = cellHash(cellX, cellZ, arena.getTerrainSeed() + 907);
        int inset = Math.min(4, cellSize / 3);
        int span = Math.max(1, cellSize - inset * 2);
        int anchorX = cellX * cellSize + inset + hash % span;
        int anchorZ = cellZ * cellSize + inset + (hash / span) % span;
        if (x != anchorX || z != anchorZ) {
            return;
        }
        int chance = (int) Math.round(Math.max(0.0, Math.min(1.0,
                config.getDouble("netherIslandChance", 0.28))) * 1000);
        if (hash % 1000 >= chance) {
            return;
        }
        if (!CircleUtil.isInsideCircle(cx, cz, x, z, arena.getRadius() - 8)) {
            return;
        }
        if (CircleUtil.isInsideCircle(cx, cz, x, z, VolcanoCaldera.NO_TREE_RADIUS + 16)) {
            return;
        }

        int groundY = VolcanoHeatUtil.findTerrainGroundY(world, x, z, scanBaseY);
        int canopyY = findCanopyTopY(world, x, z, groundY);
        int minHover = Math.max(12, config.getInt("netherIslandMinHover", 28));
        int maxHover = Math.max(minHover, config.getInt("netherIslandMaxHover", 52));
        int hover = minHover + hash % (maxHover - minHover + 1);
        int centerY = canopyY + hover;
        int worldMax = world.getMaxHeight() - 6;
        if (centerY > worldMax) {
            centerY = worldMax;
        }
        if (centerY <= canopyY + 8) {
            return;
        }
        if (!world.getBlockAt(x, centerY, z).getType().isAir()) {
            return;
        }

        int minRadius = Math.max(3, config.getInt("netherIslandMinRadius", 4));
        int maxRadius = Math.max(minRadius, config.getInt("netherIslandMaxRadius", 8));
        int radius = minRadius + (hash / 17) % (maxRadius - minRadius + 1);
        int minThick = Math.max(2, config.getInt("netherIslandMinThickness", 2));
        int maxThick = Math.max(minThick, config.getInt("netherIslandMaxThickness", 4));
        int thickness = minThick + (hash / 41) % (maxThick - minThick + 1);
        placeNetherrackIsland(world, x, centerY, z, radius, thickness, hash);
    }

    private void placeNetherrackIsland(World world, int icx, int icy, int icz,
                                       int radius, int thickness, int hash) {
        boolean crimson = isCrimsonColumn(icx, icz);
        double radiusSq = radius * radius;
        int drop = 1 + hash % 2;
        for (int dx = -radius - 1; dx <= radius + 1; dx++) {
            for (int dz = -radius - 1; dz <= radius + 1; dz++) {
                for (int dy = -thickness - drop; dy <= thickness; dy++) {
                    double nx = dx + 0.35 * NoiseUtil.noise2D(icx + dx, icz + dz, hash);
                    double ny = dy + 0.2 * NoiseUtil.noise2D(icx + dx, icy + dy, hash + 5);
                    double nz = dz + 0.35 * NoiseUtil.noise2D(icz + dz, icy + dy, hash + 11);
                    double dist = (nx * nx + nz * nz) / radiusSq
                            + (ny * ny) / ((thickness + drop * 0.4) * (thickness + drop * 0.4) + 0.01);
                    double edge = 0.82 + NoiseUtil.noise2D(icx + dx, icz + dz, icy + dy + 29) * 0.18;
                    if (dist > edge) {
                        continue;
                    }
                    Block block = world.getBlockAt(icx + dx, icy + dy, icz + dz);
                    if (!block.getType().isAir()) {
                        continue;
                    }
                    block.setType(islandFill(dist, icx + dx, icy + dy, icz + dz, hash), false);
                }
            }
        }
        int scan = radius + 1;
        for (int dx = -scan; dx <= scan; dx++) {
            for (int dz = -scan; dz <= scan; dz++) {
                int topY = -1;
                for (int dy = thickness; dy >= -thickness - drop; dy--) {
                    Block block = world.getBlockAt(icx + dx, icy + dy, icz + dz);
                    if (block.getType() != Material.NETHERRACK
                            && block.getType() != Material.MAGMA_BLOCK
                            && block.getType() != Material.SOUL_SOIL
                            && block.getType() != Material.BLACKSTONE) {
                        continue;
                    }
                    topY = icy + dy;
                    break;
                }
                if (topY < 0) {
                    continue;
                }
                Block above = world.getBlockAt(icx + dx, topY + 1, icz + dz);
                if (!above.getType().isAir()) {
                    continue;
                }
                Block top = world.getBlockAt(icx + dx, topY, icz + dz);
                if (top.getType() != Material.NETHERRACK) {
                    continue;
                }
                double roll = NoiseUtil.noise2D(icx + dx, icz + dz, hash + 61);
                if (roll > 0.12) {
                    top.setType(crimson ? Material.CRIMSON_NYLIUM : Material.WARPED_NYLIUM, false);
                    if (roll > 0.72 && above.getType().isAir()) {
                        above.setType(crimson ? Material.CRIMSON_FUNGUS : Material.WARPED_FUNGUS, false);
                    } else if (roll > 0.55 && above.getType().isAir()) {
                        above.setType(crimson ? Material.CRIMSON_ROOTS : Material.WARPED_ROOTS, false);
                    }
                }
            }
        }
    }

    private static Material islandFill(double dist, int x, int y, int z, int hash) {
        double vein = NoiseUtil.noise2D(x, z, y + 7);
        if (vein > 0.78) {
            return Material.MAGMA_BLOCK;
        }
        if (dist > 0.62 && NoiseUtil.noise2D(x, z, hash + 13) > 0.45) {
            return Material.SOUL_SOIL;
        }
        if (NoiseUtil.noise2D(x, y, hash + 23) > 0.82) {
            return Material.BLACKSTONE;
        }
        return Material.NETHERRACK;
    }

    private void placeGlowstoneBlob(World world, int cx, int cy, int cz, int radius, int hash) {
        double radiusSq = radius * radius;
        int drop = 1 + hash % 3;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -radius - drop; dy <= radius; dy++) {
                    double nx = dx + 0.15 * NoiseUtil.noise2D(cx + dx, cz + dz, hash);
                    double ny = dy + 0.2 * NoiseUtil.noise2D(cx + dx, cy + dy, hash + 3);
                    double nz = dz + 0.15 * NoiseUtil.noise2D(cz + dz, cy + dy, hash + 7);
                    // Slightly elongated downward so the blob hangs like nether glowstone.
                    double dist = (nx * nx + nz * nz) / radiusSq + (ny * ny) / ((radius + drop * 0.55) * (radius + drop * 0.55));
                    double edge = 0.78 + NoiseUtil.noise2D(cx + dx, cz + dz, cy + dy + 19) * 0.22;
                    if (dist > edge) {
                        continue;
                    }
                    Block block = world.getBlockAt(cx + dx, cy + dy, cz + dz);
                    if (!block.getType().isAir()) {
                        continue;
                    }
                    block.setType(Material.GLOWSTONE, false);
                }
            }
        }
    }

    private int findCanopyTopY(World world, int x, int z, int groundY) {
        int top = groundY;
        for (int y = groundY + 1; y <= groundY + 36; y++) {
            Material type = world.getBlockAt(x, y, z).getType();
            if (type.isAir() || type == Material.LAVA || type == Material.FIRE) {
                continue;
            }
            if (type.isSolid()) {
                top = y;
            }
        }
        return top;
    }

    private static int cellHash(int cellX, int cellZ, int seed) {
        int h = cellX * 734287 ^ cellZ * 912271 ^ seed;
        h = (h ^ (h >>> 16)) * 0x45d9f3b;
        h ^= h >>> 13;
        return h & 0x7fffffff;
    }

    private Material transform(Material current, int x, int y, int z, boolean crimson, boolean surface) {
        if (current == Material.WATER
                || current == Material.OBSIDIAN
                || current == Material.CRYING_OBSIDIAN) {
            return Material.LAVA;
        }
        Material melted = meltArctic(current);
        if (melted == Material.WATER) {
            return Material.LAVA;
        }
        if (melted == Material.AIR) {
            return Material.AIR;
        }
        if (melted == Material.DIRT) {
            current = Material.DIRT;
        }
        if (current == Material.FIRE || current == Material.SOUL_FIRE) {
            return Material.AIR;
        }

        if (Tag.LEAVES.isTagged(current)) {
            if (NoiseUtil.noise2D(x, z, y + 19) > 0.72) {
                return Material.SHROOMLIGHT;
            }
            return crimson ? Material.NETHER_WART_BLOCK : Material.WARPED_WART_BLOCK;
        }
        if (isOverworldLog(current)) {
            return crimson ? Material.CRIMSON_STEM : Material.WARPED_STEM;
        }
        if (isOverworldHyphae(current)) {
            return crimson ? Material.CRIMSON_HYPHAE : Material.WARPED_HYPHAE;
        }
        if (isOverworldPlanks(current)) {
            return crimson ? Material.CRIMSON_PLANKS : Material.WARPED_PLANKS;
        }
        if (isOverworldFence(current)) {
            return crimson ? Material.CRIMSON_FENCE : Material.WARPED_FENCE;
        }
        if (current == Material.VINE) {
            return crimson ? Material.WEEPING_VINES : Material.TWISTING_VINES;
        }
        if (isOverworldPlant(current)) {
            if (NoiseUtil.noise2D(x, z, 91) > 0.35) {
                return crimson ? Material.CRIMSON_ROOTS : Material.WARPED_ROOTS;
            }
            return Material.NETHER_SPROUTS;
        }

        if (isOverworldSoil(current)) {
            if (surface && NoiseUtil.noise2D(x, z, 44) > 0.25) {
                return crimson ? Material.CRIMSON_NYLIUM : Material.WARPED_NYLIUM;
            }
            return Material.NETHERRACK;
        }
        if (isOverworldStone(current)) {
            if (surface && NoiseUtil.noise2D(x, z, 63) > 0.55) {
                return Material.SOUL_SOIL;
            }
            if (current == Material.GRAVEL || current == Material.SAND || current == Material.SANDSTONE) {
                return Material.SOUL_SAND;
            }
            return Material.NETHERRACK;
        }
        return null;
    }

    private void applyNetherBiome(World world, int x, int groundY, int z, boolean crimson) {
        Biome biome = crimson ? Biome.CRIMSON_FOREST : Biome.WARPED_FOREST;
        world.setBiome(x, groundY, z, biome);
        world.setBiome(x, Math.max(1, groundY - 8), z, biome);
        world.setBiome(x, groundY + 8, z, biome);
    }

    private boolean isNetherBiome(World world, int x, int y, int z) {
        Biome biome = world.getBiome(x, y, z);
        return biome == Biome.CRIMSON_FOREST
                || biome == Biome.WARPED_FOREST
                || biome == Biome.NETHER_WASTES
                || biome == Biome.SOUL_SAND_VALLEY
                || biome == Biome.BASALT_DELTAS;
    }

    private boolean isProtected(Block block) {
        return PROTECTED.contains(block.getType());
    }

    public static boolean isNetherGround(Material type) {
        return type == Material.NETHERRACK
                || type == Material.MAGMA_BLOCK
                || type == Material.BLACKSTONE
                || type == Material.SOUL_SAND
                || type == Material.SOUL_SOIL
                || type == Material.CRIMSON_NYLIUM
                || type == Material.WARPED_NYLIUM
                || type == Material.NETHER_BRICKS
                || type == Material.RED_NETHER_BRICKS;
    }

    private static boolean isCrimsonColumn(int x, int z) {
        return NoiseUtil.continuousNoise2D(x * 0.045, z * 0.045, 128) >= 0;
    }

    private static boolean isOverworldLog(Material type) {
        return Tag.LOGS.isTagged(type) && !isNetherStem(type);
    }

    private static boolean isNetherStem(Material type) {
        return type == Material.CRIMSON_STEM || type == Material.STRIPPED_CRIMSON_STEM
                || type == Material.CRIMSON_HYPHAE || type == Material.STRIPPED_CRIMSON_HYPHAE
                || type == Material.WARPED_STEM || type == Material.STRIPPED_WARPED_STEM
                || type == Material.WARPED_HYPHAE || type == Material.STRIPPED_WARPED_HYPHAE;
    }

    private static boolean isOverworldHyphae(Material type) {
        return type == Material.OAK_WOOD || type == Material.SPRUCE_WOOD
                || type == Material.BIRCH_WOOD || type == Material.JUNGLE_WOOD
                || type == Material.STRIPPED_OAK_WOOD || type == Material.STRIPPED_SPRUCE_WOOD
                || type == Material.STRIPPED_BIRCH_WOOD || type == Material.STRIPPED_JUNGLE_WOOD
                || type == Material.STRIPPED_JUNGLE_LOG;
    }

    private static boolean isOverworldPlanks(Material type) {
        return Tag.PLANKS.isTagged(type)
                && type != Material.CRIMSON_PLANKS
                && type != Material.WARPED_PLANKS;
    }

    private static boolean isOverworldFence(Material type) {
        return Tag.WOODEN_FENCES.isTagged(type)
                && type != Material.CRIMSON_FENCE
                && type != Material.WARPED_FENCE;
    }

    private static boolean isOverworldPlant(Material type) {
        return type == Material.SHORT_GRASS || type == Material.TALL_GRASS
                || type == Material.FERN || type == Material.LARGE_FERN
                || type == Material.DEAD_BUSH || type == Material.MOSS_CARPET
                || type == Material.PINK_PETALS || type == Material.SWEET_BERRY_BUSH;
    }

    private static boolean isOverworldSoil(Material type) {
        return type == Material.GRASS_BLOCK || type == Material.DIRT
                || type == Material.COARSE_DIRT || type == Material.PODZOL
                || type == Material.ROOTED_DIRT || type == Material.MOSS_BLOCK
                || type == Material.MUD || type == Material.FARMLAND;
    }

    private static boolean isOverworldStone(Material type) {
        return type == Material.STONE || type == Material.COBBLESTONE
                || type == Material.DEEPSLATE || type == Material.TUFF
                || type == Material.GRAVEL || type == Material.SAND || type == Material.SANDSTONE
                || type == Material.BASALT || type == Material.SMOOTH_BASALT
                || type == Material.ANDESITE || type == Material.DIORITE || type == Material.GRANITE;
    }

    private static Material meltArctic(Material current) {
        if (current == Material.SNOW || current == Material.POWDER_SNOW) {
            return Material.AIR;
        }
        // Land snow thaws to dirt (then nether soil); sea/glacier ice becomes water/lava.
        if (current == Material.SNOW_BLOCK) {
            return Material.DIRT;
        }
        if (current == Material.ICE
                || current == Material.PACKED_ICE
                || current == Material.BLUE_ICE
                || current == Material.FROSTED_ICE) {
            return Material.WATER;
        }
        return null;
    }
}
