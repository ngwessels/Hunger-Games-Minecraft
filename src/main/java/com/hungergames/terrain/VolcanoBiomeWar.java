package com.hungergames.terrain;

import com.hungergames.HungerGamesPlugin;
import com.hungergames.arena.Arena;
import com.hungergames.arena.JungleGrove;
import com.hungergames.arena.VolcanoCaldera;
import com.hungergames.config.PluginConfig;
import com.hungergames.game.Game;
import com.hungergames.game.GameState;
import com.hungergames.game.MatchPacing;
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
 * Ice-versus-volcano biome tug-of-war. The map starts frozen (generation and/or
 * a batched freeze pass). Volcanic radius follows match progress plus a decaying
 * sine so ice recaptures ground and then loses it, ending fully volcanic.
 */
public class VolcanoBiomeWar {

    private static final Set<Material> PROTECTED = Set.of(
            Material.BEDROCK, Material.CHEST, Material.TRAPPED_CHEST,
            Material.BARREL, Material.ENDER_CHEST, Material.GOLD_BLOCK, Material.BEACON,
            Material.SPAWNER, Material.IRON_BLOCK, Material.EMERALD_BLOCK,
            Material.DIAMOND_BLOCK, Material.NETHERITE_BLOCK
    );

    private final HungerGamesPlugin plugin;
    private final int[] freezeCursor = new int[1];
    private final int[] recaptureCursor = new int[1];

    private Game game;
    private Arena arena;
    private double cx;
    private double cz;
    private int scanBaseY;
    private double dominance;
    private int volcanicRadius = VolcanoCaldera.NO_TREE_RADIUS;
    private boolean freezeComplete;
    private BukkitTask freezeTask;
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
        startFreezeTask();
    }

    public void reset() {
        stopFreezeTask();
        freezeCursor[0] = 0;
        recaptureCursor[0] = 0;
        freezeComplete = false;
        dominance = 0;
        volcanicRadius = VolcanoCaldera.NO_TREE_RADIUS;
        groveArea = null;
        game = null;
        arena = null;
    }

    public double getVolcanicDominance() {
        return dominance;
    }

    public double getIceDominance() {
        return Math.max(0.0, 1.0 - dominance);
    }

    public int getVolcanicRadius() {
        return volcanicRadius;
    }

    public boolean isVolcanicColumn(int x, int z) {
        return CircleUtil.distanceFromCenter(cx, cz, x, z) <= volcanicRadius
                + NoiseUtil.continuousNoise2D(x * 0.07, z * 0.07, 71) * 5.0;
    }

    /**
     * 0 = full ice, 1 = full volcano. Oscillation amplitude decays so ice can
     * surge back mid-match and still lose by the end.
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
     * Drive nether expansion or ice recapture toward the oscillating target.
     * {@code protectedRadius} is the volcano cone — ice never overwrites it.
     */
    public void tickConversion(World world, Arena matchArena, Game match, TerrainPhaseConfig config,
                               VolcanoNetherSpread netherSpread, int protectedRadius) {
        if (world == null || netherSpread == null || !isEnabled(config)) {
            return;
        }
        PluginConfig pluginConfig = plugin.getPluginConfig();
        dominance = computeDominance(match, pluginConfig, config);
        int target = computeVolcanicRadius(matchArena, match, pluginConfig, config);
        int floor = Math.max(VolcanoCaldera.NO_TREE_RADIUS, protectedRadius);
        target = Math.max(floor, target);
        volcanicRadius = target;

        int current = netherSpread.getConvertedRadius();
        if (target >= current) {
            netherSpread.tick(world, matchArena, match.getGameMode(), config, target);
            return;
        }
        if (!config.getBoolean("biomeWarIceRecaptureEnabled", true)) {
            return;
        }
        recaptureIce(world, matchArena, config, netherSpread, target, current);
        netherSpread.setConvertedRadius(target);
    }

    private void startFreezeTask() {
        stopFreezeTask();
        freezeComplete = false;
        freezeCursor[0] = 0;
        freezeTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (game == null || arena == null) {
                    cancel();
                    freezeTask = null;
                    return;
                }
                GameState state = game.getState();
                if (state != GameState.GRACE && state != GameState.ACTIVE) {
                    cancel();
                    freezeTask = null;
                    return;
                }
                World world = arena.getWorld();
                if (world == null) {
                    cancel();
                    freezeTask = null;
                    return;
                }
                if (tickFreezeBatch(world, arena)) {
                    freezeComplete = true;
                    cancel();
                    freezeTask = null;
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private void stopFreezeTask() {
        if (freezeTask != null) {
            freezeTask.cancel();
            freezeTask = null;
        }
    }

    /**
     * Converts remaining overworld columns to ice so existing forest templates
     * become an ice world without waiting for a regenerate. Already-ice columns
     * are cheap no-ops. Returns true when the disc has been fully scanned.
     */
    private boolean tickFreezeBatch(World world, Arena matchArena) {
        TerrainPhaseConfig config = plugin.getTerrainPhaseManager().getCurrentPhaseConfig();
        int columns = 400;
        if (config != null) {
            columns = Math.max(40, config.getInt("biomeWarFreezeColumnsPerTick", 400));
        }
        int radius = Math.max(1, matchArena.getRadius() - 2);
        int cxBlock = (int) cx;
        int czBlock = (int) cz;
        int side = 2 * radius + 1;
        int totalCells = side * side;
        int start = freezeCursor[0];
        int converted = 0;
        while (converted < columns) {
            int idx = freezeCursor[0] % totalCells;
            freezeCursor[0]++;
            if (freezeCursor[0] - start > totalCells) {
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
            if (isVolcanicColumn(x, z) && volcanicRadius > VolcanoCaldera.NO_TREE_RADIUS + 2) {
                continue;
            }
            freezeColumn(world, x, z, matchArena.getTerrainSeed());
            converted++;
        }
        return freezeCursor[0] - start > totalCells;
    }

    private void freezeColumn(World world, int x, int z, int seed) {
        int groundY = VolcanoHeatUtil.findTerrainGroundY(world, x, z, scanBaseY);
        int minY = Math.max(1, groundY - 4);
        int maxY = groundY + 6;
        boolean crimsonNoise = NoiseUtil.continuousNoise2D(x * 0.06, z * 0.06, seed + 401) > 0.08;
        for (int y = minY; y <= maxY; y++) {
            Block block = world.getBlockAt(x, y, z);
            Material current = block.getType();
            if (current.isAir() || current == Material.LAVA || PROTECTED.contains(current)) {
                continue;
            }
            if (isAlreadyIce(current) || VolcanoNetherSpread.isNetherGround(current)) {
                continue;
            }
            Material next = iceReplacement(current, x, y, z, groundY, crimsonNoise);
            if (next != null && next != current) {
                block.setType(next, false);
            }
        }
        world.setBiome(x, groundY, z, Biome.SNOWY_PLAINS);
        world.setBiome(x, Math.max(1, groundY - 8), z, Biome.SNOWY_PLAINS);
        world.setBiome(x, groundY + 8, z, Biome.SNOWY_PLAINS);
    }

    private void recaptureIce(World world, Arena matchArena, TerrainPhaseConfig config,
                              VolcanoNetherSpread netherSpread, int targetRadius, int currentRadius) {
        int columns = Math.max(8, config.getInt("biomeWarRecaptureColumnsPerTick", 56));
        int scanRadius = Math.min(matchArena.getRadius() - 2, currentRadius + 4);
        int cxBlock = (int) cx;
        int czBlock = (int) cz;
        int side = 2 * scanRadius + 1;
        int totalCells = side * side;
        if (totalCells <= 0) {
            return;
        }
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
            double dist = Math.sqrt(distSq);
            if (dist <= targetRadius) {
                continue;
            }
            if (netherSpread.contains(x, z) && dist <= targetRadius + 6) {
                continue;
            }
            if (recaptureColumn(world, x, z, matchArena.getTerrainSeed())) {
                converted++;
            }
        }
    }

    private boolean recaptureColumn(World world, int x, int z, int seed) {
        int groundY = VolcanoHeatUtil.findTerrainGroundY(world, x, z, scanBaseY);
        int minY = Math.max(1, groundY - 6);
        int maxY = groundY + 40;
        boolean packed = NoiseUtil.continuousNoise2D(x * 0.06, z * 0.06, seed + 401) > 0.08;
        boolean changed = false;
        for (int y = minY; y <= maxY; y++) {
            Block block = world.getBlockAt(x, y, z);
            Material current = block.getType();
            if (current.isAir() || PROTECTED.contains(current)) {
                continue;
            }
            Material next = iceReplacement(current, x, y, z, groundY, packed);
            if (next != null && next != current) {
                block.setType(next, false);
                changed = true;
            }
        }
        if (changed) {
            world.setBiome(x, groundY, z, Biome.SNOWY_PLAINS);
            world.setBiome(x, Math.max(1, groundY - 8), z, Biome.SNOWY_PLAINS);
            world.setBiome(x, groundY + 8, z, Biome.SNOWY_PLAINS);
        }
        return changed;
    }

    private static Material iceReplacement(Material current, int x, int y, int z, int groundY,
                                           boolean packedBias) {
        if (current == Material.LAVA || current == Material.WATER) {
            return y >= groundY ? Material.ICE : Material.PACKED_ICE;
        }
        if (current == Material.FIRE || current == Material.SOUL_FIRE
                || current == Material.CRIMSON_ROOTS || current == Material.WARPED_ROOTS
                || current == Material.NETHER_SPROUTS || current == Material.SHORT_GRASS
                || current == Material.TALL_GRASS || current == Material.FERN
                || current == Material.DEAD_BUSH || current == Material.CRIMSON_FUNGUS
                || current == Material.WARPED_FUNGUS || current == Material.WEEPING_VINES
                || current == Material.TWISTING_VINES) {
            return y > groundY ? Material.SNOW : Material.AIR;
        }
        if (Tag.LEAVES.isTagged(current) || current == Material.NETHER_WART_BLOCK
                || current == Material.WARPED_WART_BLOCK || current == Material.SHROOMLIGHT) {
            return Material.PACKED_ICE;
        }
        if (Tag.LOGS.isTagged(current) || Tag.PLANKS.isTagged(current)
                || Tag.WOODEN_FENCES.isTagged(current)) {
            return packedBias ? Material.PACKED_ICE : Material.BLUE_ICE;
        }
        if (isAlreadyIce(current)) {
            return current;
        }
        if (y == groundY) {
            double n = NoiseUtil.continuousFractalNoise2D(x * 0.06, z * 0.06, 401, 3);
            if (n > 0.38) {
                return Material.PACKED_ICE;
            }
            if (n > 0.08) {
                return Material.SNOW_BLOCK;
            }
            return Material.ICE;
        }
        if (y > groundY) {
            return Material.SNOW;
        }
        int depth = groundY - y;
        if (depth <= 2) {
            return Material.SNOW_BLOCK;
        }
        if (depth <= 5) {
            return packedBias ? Material.PACKED_ICE : Material.SNOW_BLOCK;
        }
        return null;
    }

    private static boolean isAlreadyIce(Material type) {
        return type == Material.ICE
                || type == Material.PACKED_ICE
                || type == Material.BLUE_ICE
                || type == Material.FROSTED_ICE
                || type == Material.SNOW
                || type == Material.SNOW_BLOCK
                || type == Material.POWDER_SNOW;
    }
}
