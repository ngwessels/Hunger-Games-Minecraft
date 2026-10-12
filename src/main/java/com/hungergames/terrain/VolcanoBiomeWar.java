package com.hungergames.terrain;

import com.hungergames.HungerGamesPlugin;
import com.hungergames.arena.ArcticRing;
import com.hungergames.arena.Arena;
import com.hungergames.arena.JungleGrove;
import com.hungergames.arena.VolcanoCaldera;
import com.hungergames.config.PluginConfig;
import com.hungergames.game.Game;
import com.hungergames.game.GameState;
import com.hungergames.game.MatchPacing;
import com.hungergames.gamemode.ArcticConfig;
import com.hungergames.gamemode.GameMode;
import com.hungergames.gamemode.TerrainPhaseConfig;
import com.hungergames.util.CircleUtil;
import com.hungergames.util.NoiseUtil;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.Set;

/**
 * Tundra / plains / nether tug-of-war. The inner disc starts as patchy tundra
 * (generation and/or a batched ensure pass). Volcanic radius follows match
 * progress plus a decaying sine: a plains band advances ahead of nether, then
 * nether consumes it. When the front retreats, columns restore to plains then
 * tundra. The outer ice-ocean ring is never overwritten by these passes.
 */
public class VolcanoBiomeWar {

    private static final Set<Material> PROTECTED = Set.of(
            Material.BEDROCK, Material.CHEST, Material.TRAPPED_CHEST,
            Material.BARREL, Material.ENDER_CHEST, Material.GOLD_BLOCK, Material.BEACON,
            Material.SPAWNER, Material.IRON_BLOCK, Material.EMERALD_BLOCK,
            Material.DIAMOND_BLOCK, Material.NETHERITE_BLOCK
    );

    private final HungerGamesPlugin plugin;
    private final int[] tundraCursor = new int[1];
    private final int[] plainsCursor = new int[1];
    private final int[] recaptureCursor = new int[1];

    private Game game;
    private Arena arena;
    private double cx;
    private double cz;
    private int scanBaseY;
    private double dominance;
    private int volcanicRadius = VolcanoCaldera.NO_TREE_RADIUS;
    private int plainsRadius = VolcanoCaldera.NO_TREE_RADIUS;
    private BukkitTask tundraTask;
    private double[] groveArea;

    public VolcanoBiomeWar(HungerGamesPlugin plugin) {
        this.plugin = plugin;
    }

    public static boolean isEnabled(TerrainPhaseConfig config) {
        return config != null && config.getBoolean("biomeWarEnabled", true);
    }

    public void start(Game match, Arena matchArena) {
        reset();
        this.game = match;
        this.arena = matchArena;
        this.cx = matchArena.getCenterX();
        this.cz = matchArena.getCenterZ();
        this.scanBaseY = (int) matchArena.getCenterY();
        this.groveArea = JungleGrove.groveArea(matchArena, match.getGameMode());
        this.dominance = 0;
        this.volcanicRadius = VolcanoCaldera.NO_TREE_RADIUS;
        this.plainsRadius = VolcanoCaldera.NO_TREE_RADIUS;
        startTundraTask();
    }

    public void reset() {
        stopTundraTask();
        tundraCursor[0] = 0;
        plainsCursor[0] = 0;
        recaptureCursor[0] = 0;
        dominance = 0;
        volcanicRadius = VolcanoCaldera.NO_TREE_RADIUS;
        plainsRadius = VolcanoCaldera.NO_TREE_RADIUS;
        groveArea = null;
        game = null;
        arena = null;
    }

    public double getVolcanicDominance() {
        return dominance;
    }

    /** Remaining cold/tundra strength opposite volcanic dominance (ice faction scaling). */
    public double getIceDominance() {
        return Math.max(0.0, 1.0 - dominance);
    }

    public int getVolcanicRadius() {
        return volcanicRadius;
    }

    public int getPlainsRadius() {
        return plainsRadius;
    }

    public boolean isVolcanicColumn(int x, int z) {
        return CircleUtil.distanceFromCenter(cx, cz, x, z) <= volcanicRadius
                + NoiseUtil.continuousNoise2D(x * 0.07, z * 0.07, 71) * 5.0;
    }

    /**
     * 0 = full tundra, 1 = full volcano. Oscillation amplitude decays so cold
     * terrain can surge back mid-match and still lose by the end.
     */
    public double computeDominance(Game match, PluginConfig pluginConfig, TerrainPhaseConfig config) {
        double progress = MatchPacing.getMatchProgress(match, pluginConfig);
        if (progress < 0) {
            progress = 0;
        }
        progress = Math.min(1.0, Math.max(0.0, progress));
        double power = Math.max(0.4, config.getDouble("biomeWarProgressPower", 1.15));
        double cycles = Math.max(0.0, config.getDouble("biomeWarOscillationCycles", 3.0));
        double amplitude = Math.max(0.0, config.getDouble("biomeWarOscillationAmplitude", 0.22));
        double dampening = Math.max(0.2, config.getDouble("biomeWarOscillationDampening", 1.4));
        double iceGoneAt = Math.min(1.0, Math.max(0.5, config.getDouble("biomeWarIceGoneMatchRatio", 0.92)));

        double trend = Math.pow(progress, power);
        double wave = amplitude * Math.sin(2.0 * Math.PI * cycles * progress)
                * Math.pow(1.0 - progress, dampening);
        double value = trend + wave;
        if (progress >= iceGoneAt) {
            double t = (progress - iceGoneAt) / Math.max(1.0e-6, 1.0 - iceGoneAt);
            value = value + (1.0 - value) * t;
        }
        if (progress >= 1.0) {
            return 1.0;
        }
        return Math.min(1.0, Math.max(0.0, value));
    }

    public int computeVolcanicRadius(Arena matchArena, Game match, PluginConfig pluginConfig,
                                     TerrainPhaseConfig config) {
        int minRadius = VolcanoCaldera.NO_TREE_RADIUS;
        int maxRadius = Math.max(minRadius, matchArena.getRadius() - 2);
        double scale = config.getDouble("netherSpreadRadiusScale", 1.0);
        double value = computeDominance(match, pluginConfig, config);
        return minRadius + (int) Math.round((maxRadius - minRadius) * value * scale);
    }

    /**
     * Drive plains + nether expansion or cold recapture toward the oscillating target.
     * {@code protectedRadius} is the volcano cone — cold terrain never overwrites it.
     */
    public void tickConversion(World world, Arena matchArena, Game match, TerrainPhaseConfig config,
                               VolcanoNetherSpread netherSpread, int protectedRadius) {
        if (world == null || netherSpread == null || !isEnabled(config)) {
            return;
        }
        PluginConfig pluginConfig = plugin.getPluginConfig();
        dominance = computeDominance(match, pluginConfig, config);
        int targetNether = computeVolcanicRadius(matchArena, match, pluginConfig, config);
        int floor = Math.max(VolcanoCaldera.NO_TREE_RADIUS, protectedRadius);
        targetNether = Math.max(floor, targetNether);
        volcanicRadius = targetNether;

        int forestCap = match.getGameMode().getForestRadius();
        int plainsLead = Math.max(0, config.getInt("biomeWarPlainsLeadBlocks", 48));
        plainsRadius = Math.min(forestCap, Math.max(targetNether, targetNether + plainsLead));

        int current = netherSpread.getConvertedRadius();
        if (targetNether >= current) {
            convertPlainsBand(world, matchArena, config, targetNether, plainsRadius);
            netherSpread.tick(world, matchArena, match.getGameMode(), config, targetNether);
            return;
        }
        if (!config.getBoolean("biomeWarIceRecaptureEnabled", true)) {
            return;
        }
        recaptureCold(world, matchArena, config, netherSpread, targetNether, current, plainsRadius);
        netherSpread.setConvertedRadius(targetNether);
    }

    private void startTundraTask() {
        stopTundraTask();
        tundraCursor[0] = 0;
        tundraTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (game == null || arena == null) {
                    cancel();
                    tundraTask = null;
                    return;
                }
                GameState state = game.getState();
                if (state != GameState.GRACE && state != GameState.ACTIVE) {
                    cancel();
                    tundraTask = null;
                    return;
                }
                World world = arena.getWorld();
                if (world == null) {
                    cancel();
                    tundraTask = null;
                    return;
                }
                if (tickTundraBatch(world, arena, game.getGameMode())) {
                    cancel();
                    tundraTask = null;
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private void stopTundraTask() {
        if (tundraTask != null) {
            tundraTask.cancel();
            tundraTask = null;
        }
    }

    /**
     * Ensures the inner disc is patchy tundra so older forest/ice templates
     * match the new layout without a full regenerate. Outer ice ocean and
     * caldera/grove columns are skipped. Returns true when fully scanned.
     */
    private boolean tickTundraBatch(World world, Arena matchArena, GameMode gameMode) {
        TerrainPhaseConfig config = plugin.getTerrainPhaseManager().getCurrentPhaseConfig();
        int columns = 400;
        if (config != null) {
            columns = Math.max(40, config.getInt("biomeWarFreezeColumnsPerTick", 400));
        }
        ArcticConfig arctic = gameMode != null ? gameMode.getArctic() : null;
        if (arctic == null || !arctic.isEnabled()) {
            return true;
        }
        int forest = gameMode.getForestRadius();
        int radius = Math.max(1, Math.min(forest, matchArena.getRadius() - 2));
        int cxBlock = (int) cx;
        int czBlock = (int) cz;
        int side = 2 * radius + 1;
        int totalCells = side * side;
        int start = tundraCursor[0];
        int converted = 0;
        int seed = matchArena.getTerrainSeed();
        while (converted < columns) {
            int idx = tundraCursor[0] % totalCells;
            tundraCursor[0]++;
            if (tundraCursor[0] - start > totalCells) {
                return true;
            }
            int dx = idx % side - radius;
            int dz = idx / side - radius;
            if (dx * dx + dz * dz > radius * radius) {
                continue;
            }
            int x = cxBlock + dx;
            int z = czBlock + dz;
            if (VolcanoCaldera.isInsideCalderaArea(cx, cz, x, z)) {
                continue;
            }
            if (JungleGrove.isInside(groveArea, x, z)) {
                continue;
            }
            if (ArcticRing.isArcticColumn(matchArena, gameMode, x, z)) {
                continue;
            }
            if (isVolcanicColumn(x, z) && volcanicRadius > VolcanoCaldera.NO_TREE_RADIUS + 2) {
                continue;
            }
            if (applyTundraColumn(world, x, z, seed, arctic)) {
                converted++;
            }
        }
        return tundraCursor[0] - start > totalCells;
    }

    private void convertPlainsBand(World world, Arena matchArena, TerrainPhaseConfig config,
                                   int netherRadius, int targetPlains) {
        int columns = Math.max(8, config.getInt("biomeWarPlainsColumnsPerTick", 64));
        int forest = game != null ? game.getGameMode().getForestRadius() : matchArena.getRadius();
        int scanRadius = Math.min(forest, Math.min(matchArena.getRadius() - 2, targetPlains + 4));
        int cxBlock = (int) cx;
        int czBlock = (int) cz;
        int side = 2 * scanRadius + 1;
        int totalCells = side * side;
        if (totalCells <= 0) {
            return;
        }
        int converted = 0;
        int start = plainsCursor[0];
        int seed = matchArena.getTerrainSeed();
        while (converted < columns) {
            int idx = plainsCursor[0] % totalCells;
            plainsCursor[0]++;
            if (plainsCursor[0] - start > totalCells) {
                break;
            }
            int dx = idx % side - scanRadius;
            int dz = idx / side - scanRadius;
            int distSq = dx * dx + dz * dz;
            if (distSq > scanRadius * scanRadius) {
                continue;
            }
            double dist = Math.sqrt(distSq);
            if (dist <= netherRadius || dist > targetPlains) {
                continue;
            }
            int x = cxBlock + dx;
            int z = czBlock + dz;
            if (VolcanoCaldera.isInsideCalderaArea(cx, cz, x, z)) {
                continue;
            }
            if (JungleGrove.isInside(groveArea, x, z)) {
                continue;
            }
            if (applyPlainsColumn(world, x, z, seed)) {
                converted++;
            }
        }
    }

    private void recaptureCold(World world, Arena matchArena, TerrainPhaseConfig config,
                               VolcanoNetherSpread netherSpread, int targetNether, int currentNether,
                               int targetPlains) {
        int columns = Math.max(8, config.getInt("biomeWarRecaptureColumnsPerTick", 56));
        int forest = game != null ? game.getGameMode().getForestRadius() : matchArena.getRadius();
        int scanRadius = Math.min(matchArena.getRadius() - 2, Math.max(currentNether, targetPlains) + 4);
        int cxBlock = (int) cx;
        int czBlock = (int) cz;
        int side = 2 * scanRadius + 1;
        int totalCells = side * side;
        if (totalCells <= 0) {
            return;
        }
        ArcticConfig arctic = game != null ? game.getGameMode().getArctic() : null;
        int seed = matchArena.getTerrainSeed();
        int converted = 0;
        int start = recaptureCursor[0];
        while (converted < columns) {
            int idx = recaptureCursor[0] % totalCells;
            recaptureCursor[0]++;
            if (recaptureCursor[0] - start > totalCells) {
                break;
            }
            int dx = idx % side - scanRadius;
            int dz = idx / side - scanRadius;
            int distSq = dx * dx + dz * dz;
            if (distSq > scanRadius * scanRadius) {
                continue;
            }
            int x = cxBlock + dx;
            int z = czBlock + dz;
            if (VolcanoCaldera.isInsideCalderaArea(cx, cz, x, z)) {
                continue;
            }
            if (ArcticRing.isArcticColumn(matchArena, game.getGameMode(), x, z)) {
                continue;
            }
            double dist = Math.sqrt(distSq);
            if (dist <= targetNether) {
                continue;
            }
            if (netherSpread.contains(x, z) && dist <= targetNether + 6) {
                continue;
            }
            boolean changed;
            if (dist <= targetPlains && dist <= forest) {
                changed = applyPlainsColumn(world, x, z, seed);
            } else if (dist <= forest && arctic != null) {
                changed = applyTundraColumn(world, x, z, seed, arctic);
            } else {
                continue;
            }
            if (changed) {
                converted++;
            }
        }
    }

    private boolean applyTundraColumn(World world, int x, int z, int seed, ArcticConfig arctic) {
        int groundY = VolcanoHeatUtil.findTerrainGroundY(world, x, z, scanBaseY);
        int minY = Math.max(1, groundY - 4);
        int maxY = groundY + 6;
        boolean snowy = ArcticRing.isSnowyTundraPatch(arctic, x, z, seed);
        boolean changed = false;
        for (int y = minY; y <= maxY; y++) {
            Block block = world.getBlockAt(x, y, z);
            Material current = block.getType();
            if (current.isAir() && y != groundY + 1) {
                continue;
            }
            if (current == Material.LAVA || PROTECTED.contains(current)) {
                continue;
            }
            if (VolcanoNetherSpread.isNetherGround(current) && y < groundY) {
                continue;
            }
            Material next = tundraReplacement(current, x, y, z, groundY, snowy, seed, arctic);
            if (next != null && next != current) {
                block.setType(next, false);
                changed = true;
            }
        }
        Biome biome = snowy ? Biome.SNOWY_PLAINS : Biome.SNOWY_TAIGA;
        world.setBiome(x, groundY, z, biome);
        world.setBiome(x, Math.max(1, groundY - 8), z, biome);
        world.setBiome(x, groundY + 8, z, biome);
        return changed;
    }

    private boolean applyPlainsColumn(World world, int x, int z, int ignoredSeed) {
        int groundY = VolcanoHeatUtil.findTerrainGroundY(world, x, z, scanBaseY);
        int minY = Math.max(1, groundY - 4);
        int maxY = groundY + 40;
        boolean changed = false;
        for (int y = minY; y <= maxY; y++) {
            Block block = world.getBlockAt(x, y, z);
            Material current = block.getType();
            if (current.isAir() || PROTECTED.contains(current)) {
                continue;
            }
            Material next = plainsReplacement(current, y, groundY);
            if (next != null && next != current) {
                block.setType(next, false);
                changed = true;
            }
        }
        if (changed || world.getBiome(x, groundY, z) != Biome.PLAINS) {
            world.setBiome(x, groundY, z, Biome.PLAINS);
            world.setBiome(x, Math.max(1, groundY - 8), z, Biome.PLAINS);
            world.setBiome(x, groundY + 8, z, Biome.PLAINS);
            changed = true;
        }
        return changed;
    }

    private static Material tundraReplacement(Material current, int x, int y, int z, int groundY,
                                              boolean snowy, int seed, ArcticConfig arctic) {
        if (current == Material.LAVA) {
            return null;
        }
        if (current == Material.WATER) {
            return y >= groundY ? Material.ICE : Material.PACKED_ICE;
        }
        if (current == Material.FIRE || current == Material.SOUL_FIRE
                || current == Material.CRIMSON_ROOTS || current == Material.WARPED_ROOTS
                || current == Material.NETHER_SPROUTS || current == Material.SHORT_GRASS
                || current == Material.TALL_GRASS || current == Material.FERN
                || current == Material.DEAD_BUSH || current == Material.CRIMSON_FUNGUS
                || current == Material.WARPED_FUNGUS || current == Material.WEEPING_VINES
                || current == Material.TWISTING_VINES) {
            if (y > groundY) {
                return snowy ? Material.SNOW : Material.AIR;
            }
            return Material.AIR;
        }
        if (Tag.LEAVES.isTagged(current) || current == Material.NETHER_WART_BLOCK
                || current == Material.WARPED_WART_BLOCK || current == Material.SHROOMLIGHT) {
            return current;
        }
        if (Tag.LOGS.isTagged(current) || Tag.PLANKS.isTagged(current)
                || Tag.WOODEN_FENCES.isTagged(current)
                || VolcanoNetherSpread.isNetherGround(current)) {
            if (y == groundY) {
                return ArcticRing.tundraSurfaceAt(arctic, x, z, seed);
            }
            if (y > groundY) {
                return Material.AIR;
            }
            return ArcticRing.tundraSubsurfaceAt(arctic, x, y, z, groundY, seed);
        }
        if (isColdSurface(current) || isWarmOverworldSurface(current)) {
            if (y == groundY) {
                return ArcticRing.tundraSurfaceAt(arctic, x, z, seed);
            }
            if (y > groundY) {
                return snowy ? Material.SNOW : null;
            }
            return ArcticRing.tundraSubsurfaceAt(arctic, x, y, z, groundY, seed);
        }
        if (y == groundY) {
            return ArcticRing.tundraSurfaceAt(arctic, x, z, seed);
        }
        if (y == groundY + 1 && snowy && (current.isAir() || current == Material.SNOW)) {
            return Material.SNOW;
        }
        return null;
    }

    private static Material plainsReplacement(Material current, int y, int groundY) {
        if (current == Material.LAVA && y >= groundY) {
            return Material.AIR;
        }
        if (current == Material.SNOW || current == Material.POWDER_SNOW) {
            return Material.AIR;
        }
        if (current == Material.SNOW_BLOCK || current == Material.ICE
                || current == Material.PACKED_ICE || current == Material.BLUE_ICE
                || current == Material.FROSTED_ICE) {
            if (y == groundY) {
                return Material.GRASS_BLOCK;
            }
            if (y > groundY) {
                return Material.AIR;
            }
            return Material.DIRT;
        }
        if (VolcanoNetherSpread.isNetherGround(current)
                || current == Material.MAGMA_BLOCK
                || current == Material.CRIMSON_NYLIUM
                || current == Material.WARPED_NYLIUM
                || current == Material.NETHER_WART_BLOCK
                || current == Material.WARPED_WART_BLOCK
                || current == Material.SHROOMLIGHT
                || current == Material.SOUL_SAND
                || current == Material.SOUL_SOIL
                || current == Material.CRIMSON_STEM
                || current == Material.WARPED_STEM
                || current == Material.CRIMSON_HYPHAE
                || current == Material.WARPED_HYPHAE
                || current == Material.CRIMSON_PLANKS
                || current == Material.WARPED_PLANKS
                || current == Material.CRIMSON_ROOTS
                || current == Material.WARPED_ROOTS
                || current == Material.NETHER_SPROUTS
                || current == Material.WEEPING_VINES
                || current == Material.TWISTING_VINES
                || current == Material.SOUL_FIRE) {
            if (y == groundY) {
                return Material.GRASS_BLOCK;
            }
            if (y > groundY) {
                return Material.AIR;
            }
            int depth = groundY - y;
            if (depth <= 3) {
                return Material.DIRT;
            }
            return Material.STONE;
        }
        if (current == Material.COARSE_DIRT || current == Material.PODZOL) {
            return y == groundY ? Material.GRASS_BLOCK : current;
        }
        return null;
    }

    private static boolean isColdSurface(Material type) {
        return type == Material.ICE
                || type == Material.PACKED_ICE
                || type == Material.BLUE_ICE
                || type == Material.FROSTED_ICE
                || type == Material.SNOW
                || type == Material.SNOW_BLOCK
                || type == Material.POWDER_SNOW;
    }

    private static boolean isWarmOverworldSurface(Material type) {
        return type == Material.GRASS_BLOCK
                || type == Material.DIRT
                || type == Material.COARSE_DIRT
                || type == Material.PODZOL
                || type == Material.ROOTED_DIRT
                || type == Material.BASALT
                || type == Material.SMOOTH_BASALT
                || type == Material.GRAVEL;
    }
}
