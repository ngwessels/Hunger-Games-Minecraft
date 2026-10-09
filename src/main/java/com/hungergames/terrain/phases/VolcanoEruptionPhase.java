package com.hungergames.terrain.phases;

import com.hungergames.HungerGamesPlugin;
import com.hungergames.arena.Arena;
import com.hungergames.arena.VolcanoCaldera;
import com.hungergames.game.Game;
import com.hungergames.game.MatchPacing;
import com.hungergames.gamemode.TerrainPhaseConfig;
import com.hungergames.powerup.PowerUpDefinition;
import com.hungergames.terrain.ArcticMeltSurge;
import com.hungergames.terrain.FireTornado;
import com.hungergames.terrain.TerrainPhase;
import com.hungergames.terrain.VolcanoBiomeFactions;
import com.hungergames.terrain.VolcanoBiomeWar;
import com.hungergames.terrain.VolcanoGuardian;
import com.hungergames.terrain.VolcanoHeatUtil;
import com.hungergames.terrain.VolcanoLavaFlow;
import com.hungergames.terrain.VolcanoNetherSpread;
import com.hungergames.terrain.VolcanoPiglinHorde;
import com.hungergames.util.ArenaEntityUtil;
import com.hungergames.util.ArenaScatterUtil;
import com.hungergames.util.CircleUtil;
import com.hungergames.util.ColorUtil;
import com.hungergames.util.ParticleUtil;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.Chunk;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Ghast;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;

public class VolcanoEruptionPhase implements TerrainPhase {

    private static final int VENT_RADIUS = 2;
    private static final int CONE_ARENA_EDGE_MARGIN = 3;
    private static final double TNT_DRAG = 0.98;
    private static final double TNT_GRAVITY = 0.04;
    /** Hard cap on cone block reads per tick so big cones can't stall the main thread. */
    private static final int CONE_VISIT_BUDGET_PER_TICK = 60_000;
    private static final EntityType[] EARLY_MOBS = {EntityType.BLAZE, EntityType.MAGMA_CUBE};
    private static final EntityType[] LATE_MOBS = {
            EntityType.BLAZE, EntityType.MAGMA_CUBE,
            EntityType.WITHER_SKELETON, EntityType.PIGLIN_BRUTE, EntityType.HOGLIN
    };
    private static final EntityType[] FOREST_MOBS = {
            EntityType.ZOMBIE, EntityType.SKELETON, EntityType.SPIDER
    };
    /** Original arena terrain that must be replaced inside the growing cone shell. */
    private static final Set<Material> NATURAL_TERRAIN = Set.of(
            Material.GRASS_BLOCK, Material.DIRT, Material.COARSE_DIRT, Material.PODZOL,
            Material.STONE, Material.BASALT, Material.SMOOTH_BASALT, Material.COBBLESTONE,
            Material.TUFF, Material.DEEPSLATE, Material.GRAVEL, Material.SAND, Material.SANDSTONE,
            Material.OAK_LOG, Material.OAK_LEAVES, Material.SPRUCE_LOG, Material.SPRUCE_LEAVES,
            Material.BIRCH_LOG, Material.BIRCH_LEAVES,
            Material.JUNGLE_LOG, Material.JUNGLE_LEAVES,
            Material.JUNGLE_PLANKS, Material.JUNGLE_WOOD,
            Material.STRIPPED_JUNGLE_LOG, Material.STRIPPED_JUNGLE_WOOD,
            Material.LADDER, Material.VINE, Material.JUNGLE_FENCE,
            Material.FERN, Material.MOSS_CARPET,
            Material.TALL_GRASS, Material.SHORT_GRASS, Material.DEAD_BUSH, Material.FIRE,
            Material.CRIMSON_STEM, Material.WARPED_STEM,
            Material.CRIMSON_HYPHAE, Material.WARPED_HYPHAE,
            Material.CRIMSON_PLANKS, Material.WARPED_PLANKS,
            Material.CRIMSON_FENCE, Material.WARPED_FENCE,
            Material.NETHER_WART_BLOCK, Material.WARPED_WART_BLOCK,
            Material.CRIMSON_NYLIUM, Material.WARPED_NYLIUM,
            Material.SHROOMLIGHT, Material.WEEPING_VINES, Material.TWISTING_VINES,
            Material.CRIMSON_ROOTS, Material.WARPED_ROOTS, Material.NETHER_SPROUTS,
            Material.SOUL_SAND, Material.SOUL_SOIL,
            Material.ICE, Material.PACKED_ICE, Material.BLUE_ICE,
            Material.FROSTED_ICE, Material.SNOW_BLOCK, Material.SNOW, Material.POWDER_SNOW
    );

    private final HungerGamesPlugin plugin;
    private final Random random = new Random();

    private long eruptionStartMs;
    private double tntSpawnCredit;
    private long lastVolcanoLightningMs;
    private int calderaFloorY;
    private double cx;
    private double cz;
    private int builtLayers;
    private int highestBuiltLayer;
    private int buildScanLayer;
    private int buildScanIndex;
    private int currentLayerMissing;
    private int knownCompleteLayers;
    private VolcanoLavaFlow lavaFlow;
    private final List<FireTornado> fireTornados = new ArrayList<>();
    private long lastTornadoSpawnMs;
    private VolcanoGuardian volcanoGuardian;
    private boolean volcanoGuardianSpawned;
    private boolean guardianPausedForPlayer;
    private final VolcanoPiglinHorde piglinHorde;
    private final VolcanoNetherSpread netherSpread;
    private final ArcticMeltSurge arcticMeltSurge;
    private TerrainPhaseConfig activeConfig;
    private Arena activeArena;
    private Game activeGame;
    private final int[] hotRockIgniteCursor = new int[1];
    private final int[] arenaFireBurnCursor = new int[1];
    private final int[] arenaFireDecayCursor = new int[1];
    private boolean calderaRimOpened;

    public VolcanoEruptionPhase(HungerGamesPlugin plugin) {
        this.plugin = plugin;
        this.piglinHorde = new VolcanoPiglinHorde(plugin);
        this.netherSpread = new VolcanoNetherSpread();
        this.arcticMeltSurge = new ArcticMeltSurge();
    }

    @Override
    public String id() {
        return "VOLCANO_ERUPTION";
    }

    @Override
    public void onStart(Game game, Arena arena, TerrainPhaseConfig config) {
        activeArena = arena;
        activeConfig = config;
        eruptionStartMs = System.currentTimeMillis();
        tntSpawnCredit = 0;
        lastVolcanoLightningMs = 0;
        builtLayers = 0;
        highestBuiltLayer = 0;
        buildScanLayer = 1;
        buildScanIndex = 0;
        currentLayerMissing = 0;
        knownCompleteLayers = 0;
        fireTornados.clear();
        lastTornadoSpawnMs = 0;
        if (volcanoGuardian != null) {
            volcanoGuardian.destroy();
        }
        volcanoGuardian = null;
        volcanoGuardianSpawned = false;
        guardianPausedForPlayer = false;
        hotRockIgniteCursor[0] = 0;
        arenaFireBurnCursor[0] = 0;
        arenaFireDecayCursor[0] = 0;
        calderaRimOpened = false;
        cx = arena.getCenterX();
        cz = arena.getCenterZ();
        netherSpread.initialize(cx, cz, (int) arena.getCenterY());
        arcticMeltSurge.reset();

        if (piglinHorde.isActive()) {
            piglinHorde.destroy();
        }
        if (config.getInt("piglinHordeEnabled", 1) != 0) {
            piglinHorde.configure(config);
            piglinHorde.start(game, arena);
        }

        World world = arena.getWorld();
        if (world != null) {
            calderaFloorY = findSurfaceY(world, (int) cx, (int) arena.getCenterY(), (int) cz);
            VolcanoCaldera.activateCalderaLava(world, cx, calderaFloorY + 1, cz);
            lavaFlow = new VolcanoLavaFlow();
            lavaFlow.initialize(world, cx, cz, calderaFloorY, random);
            netherSpread.initialize(cx, cz, calderaFloorY);
        }
    }

    @Override
    public void onTick(Game game, Arena arena, TerrainPhaseConfig config) {
        activeGame = game;
        activeConfig = config;
        activeArena = arena;
        World world = arena.getWorld();
        if (world == null) {
            return;
        }

        double intensity = getIntensity(game, config);
        boolean lavaActive = isLavaActive(config);
        spreadNether(world, arena, game, config);

        if (lavaActive) {
            if (lavaFlow != null) {
                double matchProgress = MatchPacing.getMatchProgress(game, plugin.getPluginConfig());
                int growthRadius = getVolcanoGrowthRadius(arena, game, config);
                lavaFlow.tick(world, arena, config, eruptionStartMs, matchProgress, growthRadius,
                        plugin.getPluginConfig().getLavaFlowSpeedMultiplier());
            }

            growVolcano(game, world, arena, config);
            igniteFlammablesNearHotRock(game, world, arena, config);
            accelerateArenaFireBurn(world, arena, config);
            decayOrphanedArenaFire(world, arena, config);
            trySpawnProjectiles(world, config);
        }

        if (VolcanoBiomeFactions.isEnabled(config)
                && plugin.getTerrainPhaseManager().getBiomeFactions() != null
                && plugin.getTerrainPhaseManager().allowsHostileSpawning(game)) {
            plugin.getTerrainPhaseManager().getBiomeFactions()
                    .tick(game, arena, world, config, plugin.getTerrainPhaseManager().getBiomeWar(), netherSpread);
        } else {
            if (intensity >= config.getDouble("mobSpawnMinIntensity", 0.25)) {
                trySpawnMobs(world, arena, config, intensity);
            }
            trySpawnForestMobs(world, arena, config, intensity);
        }

        trySpawnGhasts(world, arena, config, intensity);

        emitCalderaSmoke(world, intensity);
        playEffects(world, intensity);
        tryVolcanoLightning(world, config, intensity);
        tickFireTornado(game, arena, world, config);
        tickVolcanoGuardian(game, arena, world, config);
    }

    @Override
    public boolean isComplete() {
        return false;
    }

    @Override
    public void onEnd(Game game, Arena arena, TerrainPhaseConfig config) {
        piglinHorde.destroy();
        for (FireTornado tornado : fireTornados) {
            tornado.destroy();
        }
        fireTornados.clear();
        if (volcanoGuardian != null) {
            volcanoGuardian.destroy();
            volcanoGuardian = null;
        }
        World world = arena.getWorld();
        if (world != null) {
            ArenaEntityUtil.clearVolcanoEntities(world, arena);
        }
    }

    private boolean isLavaActive(TerrainPhaseConfig config) {
        long elapsedSec = (System.currentTimeMillis() - eruptionStartMs) / 1000L;
        return elapsedSec >= config.getInt("lavaStartDelaySeconds", 0);
    }

    private void tickFireTornado(Game game, Arena arena, World world, TerrainPhaseConfig config) {
        long matchElapsedSec = game.getMatchElapsedSeconds();
        int startSeconds = MatchPacing.deriveSeconds(
                game,
                plugin.getPluginConfig(),
                config.getInt("fireTornadoStartSeconds", 0),
                config.getDouble("fireTornadoStartMatchRatio", 0.35),
                300);

        int growthRadius = getVolcanoGrowthRadius(arena, game, config);
        int peakY = calderaFloorY + getPeakLayers();

        fireTornados.removeIf(tornado -> !tornado.isActive());
        for (FireTornado tornado : fireTornados) {
            tornado.updateGrowthRadius(growthRadius, peakY);
        }

        if (matchElapsedSec < startSeconds) {
            return;
        }
        int maxActive = config.getInt("fireTornadoMaxActive", 2);
        if (fireTornados.size() >= maxActive) {
            return;
        }
        long now = System.currentTimeMillis();
        int minIntervalSec = config.getInt("fireTornadoMinIntervalSeconds", 45);
        if (now - lastTornadoSpawnMs < minIntervalSec * 1000L) {
            return;
        }
        double chance = config.getDouble("fireTornadoSpawnChancePerTick", 0.025);
        if (random.nextDouble() > chance) {
            return;
        }

        lastTornadoSpawnMs = now;
        FireTornado tornado = new FireTornado(plugin);
        tornado.configure(config);
        Location origin = computeFireTornadoSpawn(world, growthRadius, peakY);
        tornado.spawn(game, arena, origin, cx, cz, calderaFloorY, growthRadius, peakY);
        fireTornados.add(tornado);

        String alert = plugin.formatMessage(
                "&c&lA fire tornado erupts from the volcano! &7Watch the skies!");
        for (Player player : game.getAllParticipants()) {
            player.sendMessage(alert);
            player.sendTitle(ColorUtil.color("&c&lFire Tornado"), ColorUtil.color("&7It's on the move!"), 10, 60, 20);
            player.playSound(player.getLocation(), Sound.ENTITY_WITHER_SPAWN, 1f, 0.7f);
        }
    }

    private Location computeFireTornadoSpawn(World world, int growthRadius, int peakY) {
        double angle = random.nextDouble() * Math.PI * 2;
        double minOffset = growthRadius + Math.max(8, builtLayers * 0.15 + 6);
        double spawnX = cx + Math.cos(angle) * minOffset;
        double spawnZ = cz + Math.sin(angle) * minOffset;
        return new Location(world, spawnX, peakY + 2.5, spawnZ);
    }

    private void tickVolcanoGuardian(Game game, Arena arena, World world, TerrainPhaseConfig config) {
        if (guardianPausedForPlayer) {
            return;
        }
        if (config.getInt("volcanoGuardianEnabled", 1) == 0) {
            return;
        }

        int peakY = calderaFloorY + getPeakLayers();

        if (volcanoGuardian != null && volcanoGuardian.isActive()) {
            volcanoGuardian.updateVolcanoPeakY(peakY);
            return;
        }

        if (volcanoGuardianSpawned) {
            return;
        }

        long matchElapsedSec = game.getMatchElapsedSeconds();
        int startSeconds = MatchPacing.deriveSeconds(
                game,
                plugin.getPluginConfig(),
                config.getInt("volcanoGuardianStartSeconds", 0),
                config.getDouble("volcanoGuardianStartMatchRatio", 0.65),
                780);
        if (matchElapsedSec < startSeconds) {
            return;
        }

        volcanoGuardian = new VolcanoGuardian(plugin);
        volcanoGuardian.configure(config);
        volcanoGuardian.spawn(game, arena, cx, cz, calderaFloorY, peakY);
        if (!volcanoGuardian.isActive()) {
            volcanoGuardian = null;
            return;
        }
        volcanoGuardianSpawned = true;

        String alert = plugin.formatMessage(
                "&4&lThe Volcano Guardian awakens! &7An ancient dragon rises from the caldera!");
        for (Player player : game.getAllParticipants()) {
            player.sendMessage(alert);
            player.sendTitle(
                    ColorUtil.color("&4&lVolcano Guardian"),
                    ColorUtil.color("&7The skies belong to the caldera now!"),
                    10, 70, 20);
            player.playSound(player.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 1.2f, 0.65f);
            player.playSound(player.getLocation(), Sound.ENTITY_WITHER_SPAWN, 1f, 0.55f);
        }
    }

    private int getVolcanoGrowthRadius(Arena arena, Game game, TerrainPhaseConfig config) {
        long eruptionElapsedSec = (System.currentTimeMillis() - eruptionStartMs) / 1000L;
        return getTargetBaseRadius(arena, game, config, eruptionElapsedSec);
    }

    /**
     * Nether conversion reaches the arena edge by the end of the match, independent
     * of how wide the cone itself grows.
     */
    private int getNetherSpreadTargetRadius(Arena arena, Game game, TerrainPhaseConfig config) {
        long eruptionElapsedSec = (System.currentTimeMillis() - eruptionStartMs) / 1000L;
        double growthProgress = getGrowthProgress(game, config, eruptionElapsedSec);
        double scale = config.getDouble("netherSpreadRadiusScale", 1.0);
        int minRadius = VolcanoCaldera.NO_TREE_RADIUS;
        int maxRadius = Math.max(minRadius, arena.getRadius() - 2);
        return minRadius + (int) Math.round((maxRadius - minRadius) * growthProgress * scale);
    }

    private void spreadNether(World world, Arena arena, Game game, TerrainPhaseConfig config) {
        if (config.getInt("netherSpreadEnabled", 1) == 0) {
            return;
        }
        VolcanoBiomeWar biomeWar = plugin.getTerrainPhaseManager().getBiomeWar();
        if (biomeWar != null && VolcanoBiomeWar.isEnabled(config)) {
            int protectedRadius = getVolcanoGrowthRadius(arena, game, config);
            biomeWar.tickConversion(world, arena, game, config, netherSpread, protectedRadius);
        } else {
            netherSpread.tick(world, arena, game.getGameMode(), config,
                    getNetherSpreadTargetRadius(arena, game, config));
        }
        arcticMeltSurge.tick(plugin, game, arena, config, netherSpread.getConvertedRadius());
        piglinHorde.setNetherSpreadRadius(netherSpread.getConvertedRadius());
    }

    public boolean isNetherConverted(int x, int z) {
        return netherSpread.contains(x, z);
    }

    public boolean isNetherConvertedGround(World world, int x, int z) {
        return netherSpread.isConvertedGround(world, x, z);
    }

    public int getNetherSpreadRadius() {
        return netherSpread.getConvertedRadius();
    }

    private double getIntensity(Game game, TerrainPhaseConfig config) {
        double matchProgress = MatchPacing.getMatchProgress(game, plugin.getPluginConfig());
        if (matchProgress >= 0) {
            return Math.min(3.0, matchProgress * 3.0);
        }

        int rampSeconds = MatchPacing.deriveSeconds(
                game,
                plugin.getPluginConfig(),
                config.getInt("intensityRampSeconds", 0),
                config.getDouble("intensityRampMatchRatio", 0.85),
                900);
        double elapsed = System.currentTimeMillis() - eruptionStartMs;
        return Math.min(3.0, elapsed / (rampSeconds * 1000.0));
    }

    private double getGrowthProgress(Game game, TerrainPhaseConfig config, long eruptionElapsedSec) {
        double matchProgress = MatchPacing.getMatchProgress(game, plugin.getPluginConfig());
        if (matchProgress >= 0) {
            return Math.min(1.0, matchProgress);
        }

        int rampSeconds = MatchPacing.deriveSeconds(
                game,
                plugin.getPluginConfig(),
                config.getInt("intensityRampSeconds", 0),
                config.getDouble("intensityRampMatchRatio", 0.85),
                900);
        return Math.min(1.0, eruptionElapsedSec / (double) Math.max(1, rampSeconds));
    }

    private int getMaxConeBaseRadius(Arena arena, Game game, TerrainPhaseConfig config) {
        int forestEdge = game != null ? game.getGameMode().getForestRadius() : arena.getRadius();
        int arenaEdge = Math.max(1, forestEdge - CONE_ARENA_EDGE_MARGIN);
        int configuredCap = config.getInt("maxConeBaseRadius", 0);
        if (configuredCap > 0) {
            return Math.min(arenaEdge, configuredCap);
        }
        return arenaEdge;
    }

    private int getTargetBaseRadius(Arena arena, Game game, TerrainPhaseConfig config, long eruptionElapsedSec) {
        double growthProgress = getGrowthProgress(game, config, eruptionElapsedSec);
        double baseGrowthScale = config.getDouble("coneBaseGrowthScale", 0.6);
        int minBase = config.getInt("baseRadius", 8);
        int maxBase = getMaxConeBaseRadius(arena, game, config);
        return minBase + (int) Math.round((maxBase - minBase) * growthProgress * baseGrowthScale);
    }

    private int getTargetLayers(Game game, TerrainPhaseConfig config, long eruptionElapsedSec) {
        double growthProgress = getGrowthProgress(game, config, eruptionElapsedSec);
        int maxLayers = config.getInt("maxConeLayers", 72);
        return Math.max(2, 2 + (int) Math.round((maxLayers - 2) * growthProgress));
    }

    private double coneRadiusAtLayer(int layer, int coneHeight, double baseRadius, double tipRadius) {
        if (coneHeight <= 1) {
            return baseRadius;
        }
        double progress = (layer - 1) / (double) (coneHeight - 1);
        return tipRadius + (baseRadius - tipRadius) * (1.0 - progress);
    }

    private void growVolcano(Game game, World world, Arena arena, TerrainPhaseConfig config) {
        long eruptionElapsedSec = (System.currentTimeMillis() - eruptionStartMs) / 1000L;
        double buildSpeedMultiplier = config.getDouble("coneGrowthSpeedMultiplier", 1.6);
        int baseBlocksPerTick = Math.max(1,
                (int) Math.round(config.getInt("buildBlocksPerTick", 12) * buildSpeedMultiplier));
        int targetLayers = getTargetLayers(game, config, eruptionElapsedSec);
        int targetBaseRadius = getTargetBaseRadius(arena, game, config, eruptionElapsedSec);
        double tipRadius = config.getDouble("coneTipRadius", 1.5);

        // Late game the rim backfill alone outpaces a fixed budget, which would
        // starve height growth; scale placement with the cone's footprint.
        double radiusScale = config.getDouble("buildBudgetRadiusScale", 0.08);
        int placeBudget = (int) Math.round(baseBlocksPerTick * 4 * (1.0 + targetBaseRadius * radiusScale));
        int visitBudget = CONE_VISIT_BUDGET_PER_TICK;
        int wraps = 0;

        if (buildScanLayer > targetLayers) {
            buildScanLayer = 1;
            buildScanIndex = 0;
            currentLayerMissing = 0;
        }

        // Continuous sweep: never reset mid-cycle, so upper layers always get
        // their turn no matter how large the lower discs become. The support
        // check inside scanAndFillLayer keeps growth physically bottom-up.
        while (visitBudget > 0 && placeBudget > 0) {
            double layerRadius = coneRadiusAtLayer(buildScanLayer, targetLayers, targetBaseRadius, tipRadius);
            int[] result = scanAndFillLayer(world, buildScanLayer, layerRadius,
                    placeBudget, visitBudget, buildScanIndex);
            if (result[0] > 0 && buildScanLayer > highestBuiltLayer) {
                highestBuiltLayer = buildScanLayer;
            }
            placeBudget -= result[0];
            visitBudget -= result[1];
            currentLayerMissing += result[2];
            buildScanIndex = result[4];

            if (result[3] == 0) {
                // Visit budget exhausted mid-layer; resume from buildScanIndex next tick.
                break;
            }

            boolean complete = currentLayerMissing == 0;
            if (complete) {
                if (knownCompleteLayers == buildScanLayer - 1) {
                    knownCompleteLayers = buildScanLayer;
                }
            } else if (knownCompleteLayers >= buildScanLayer) {
                knownCompleteLayers = buildScanLayer - 1;
            }
            if (!advanceScanLayer(targetLayers) && ++wraps >= 2) {
                break;
            }
        }

        // Monotonic: the lava peak never shrinks (TNT can't break it), so
        // keep launch/spawn heights stable while rims are being backfilled.
        builtLayers = Math.max(builtLayers, Math.max(1, Math.min(targetLayers, knownCompleteLayers + 1)));
        if (!calderaRimOpened && builtLayers >= 1) {
            openCalderaRimForOverflow(world);
            calderaRimOpened = true;
        }
    }

    /**
     * Removes the stone rim that cages the initial caldera lava so it can reach the cone.
     */
    private void openCalderaRimForOverflow(World world) {
        int icx = (int) cx;
        int icz = (int) cz;
        for (int dx = -VolcanoCaldera.RIM_RADIUS; dx <= VolcanoCaldera.RIM_RADIUS; dx++) {
            for (int dz = -VolcanoCaldera.RIM_RADIUS; dz <= VolcanoCaldera.RIM_RADIUS; dz++) {
                double dist = Math.sqrt(dx * dx + dz * dz);
                if (dist <= VolcanoCaldera.CALDERA_LAVA_RADIUS + 0.5
                        || dist > VolcanoCaldera.RIM_RADIUS) {
                    continue;
                }
                int x = icx + dx;
                int z = icz + dz;
                Block rim = world.getBlockAt(x, calderaFloorY, z);
                if (rim.getType() == Material.BLACKSTONE) {
                    rim.setType(Material.AIR, false);
                }
                Block under = world.getBlockAt(x, calderaFloorY - 1, z);
                if (under.getType() == Material.MAGMA_BLOCK) {
                    under.setType(Material.AIR, false);
                }
            }
        }
    }

    /** The tallest layer with any blocks placed; the physical summit can be above builtLayers. */
    private int getPeakLayers() {
        return Math.max(builtLayers, highestBuiltLayer);
    }

    private double getTntHeightProgress(TerrainPhaseConfig config) {
        int maxLayers = Math.max(1, config.getInt("maxConeLayers", 72));
        return Math.min(1.0, getPeakLayers() / (double) maxLayers);
    }

    /** Moves the sweep cursor to the next layer; returns false when it wrapped to the bottom. */
    private boolean advanceScanLayer(int targetLayers) {
        buildScanIndex = 0;
        currentLayerMissing = 0;
        buildScanLayer++;
        if (buildScanLayer > targetLayers) {
            buildScanLayer = 1;
            return false;
        }
        return true;
    }

    /**
     * Single bounded pass over one cone layer: fills missing lava while counting work.
     * Returns {placed, visited, missingUnfilled, finishedScan(0/1), resumeIndex}.
     */
    private int[] scanAndFillLayer(World world, int layer, double radius,
                                   int placeBudget, int visitBudget, int startIndex) {
        int icx = (int) cx;
        int icz = (int) cz;
        int ir = (int) Math.ceil(radius);
        int side = 2 * ir + 1;
        int totalCells = side * side;
        double radiusSq = radius * radius;
        int y = calderaFloorY + layer;

        int placed = 0;
        int visited = 0;
        int missing = 0;
        int index = Math.min(startIndex, totalCells);

        while (index < totalCells && visited < visitBudget) {
            int dx = index % side - ir;
            int dz = index / side - ir;
            index++;
            visited++;
            int distSq = dx * dx + dz * dz;
            if (distSq > radiusSq) {
                continue;
            }
            int x = icx + dx;
            int z = icz + dz;
            Block block = world.getBlockAt(x, y, z);
            if (VolcanoCaldera.isLavaSource(block)) {
                continue;
            }
            Material type = block.getType();
            if (type != Material.LAVA && !shouldReplaceForCone(type)) {
                continue;
            }

            int supportTopY = findConeSupportTopY(world, x, z, y);
            if (y - 1 > supportTopY) {
                int gapPlaced = fillColumnGaps(world, x, z, supportTopY + 1, y - 1, placeBudget - placed);
                placed += gapPlaced;
                if (placed >= placeBudget) {
                    missing++;
                    continue;
                }
            }

            Material below = world.getBlockAt(x, y - 1, z).getType();
            if (!isConeSupport(below)) {
                missing++;
                continue;
            }
            if (placed >= placeBudget) {
                missing++;
                continue;
            }
            placeConeLava(block);
            VolcanoHeatUtil.igniteFlammablesNearHotBlock(world, x, y, z);
            placed++;
        }

        return new int[]{placed, visited, missing, index >= totalCells ? 1 : 0, index};
    }

    private boolean shouldReplaceForCone(Material type) {
        return type == Material.AIR || type == Material.WATER
                || isNaturalTerrain(type)
                || type == Material.NETHERRACK || type == Material.MAGMA_BLOCK || type == Material.BLACKSTONE;
    }

    private boolean isNaturalTerrain(Material type) {
        return NATURAL_TERRAIN.contains(type);
    }

    private boolean isConeSupport(Material type) {
        return type == Material.LAVA
                || (type.isSolid() && !VolcanoHeatUtil.isFoliage(type));
    }

    /**
     * Highest lava or solid non-foliage block below the target layer. Trees are fill,
     * not support: treating canopies as ground made the cone climb tall trees
     * and grow upward away from the slope wherever the front met a grove.
     */
    private int findConeSupportTopY(World world, int x, int z, int belowY) {
        for (int y = belowY - 1; y >= 1; y--) {
            if (isConeSupport(world.getBlockAt(x, y, z).getType())) {
                return y;
            }
        }
        return calderaFloorY;
    }

    private int fillColumnGaps(World world, int x, int z, int fromY, int toY, int budget) {
        int placed = 0;
        for (int fillY = fromY; fillY <= toY && placed < budget; fillY++) {
            Block block = world.getBlockAt(x, fillY, z);
            if (VolcanoCaldera.isLavaSource(block)) {
                continue;
            }
            Material type = block.getType();
            if (type == Material.LAVA || type.isAir() || type == Material.WATER || isNaturalTerrain(type)
                    || type == Material.NETHERRACK || type == Material.MAGMA_BLOCK || type == Material.BLACKSTONE) {
                placeConeLava(block);
                VolcanoHeatUtil.igniteFlammablesNearHotBlock(world, x, fillY, z);
                placed++;
            }
        }
        return placed;
    }

    /** Source lava with physics off so the cone keeps its shape instead of flooding. */
    private static void placeConeLava(Block block) {
        block.setBlockData(VolcanoCaldera.createLavaSourceData(), false);
    }

    /** Keeps wood and leaves burning as the lava cone expands into the forest. */
    private void igniteFlammablesNearHotRock(Game game, World world, Arena arena, TerrainPhaseConfig config) {
        int growthRadius = getVolcanoGrowthRadius(arena, game, config);
        int scanRadius = growthRadius + config.getInt("hotNetherrackIgniteMargin", 8);
        int maxY = calderaFloorY + getPeakLayers() + config.getInt("hotNetherrackIgniteHeightAbovePeak", 24);
        int budget = config.getInt("hotNetherrackIgniteChecksPerTick", 400);
        VolcanoHeatUtil.tickIgniteSweep(world, cx, cz, calderaFloorY, maxY, scanRadius, budget, hotRockIgniteCursor);
    }

    /** Accelerates forest fire spread across the arena; fire tornado zones keep their own burn rate. */
    private void accelerateArenaFireBurn(World world, Arena arena, TerrainPhaseConfig config) {
        double burnChance = config.getDouble("arenaFireBurnChancePerTick", 0.08);
        if (burnChance <= 0) {
            return;
        }
        int scanRadius = Math.max(1, arena.getRadius() - 2);
        int maxY = calderaFloorY + getPeakLayers() + config.getInt("hotNetherrackIgniteHeightAbovePeak", 24);
        int budget = config.getInt("arenaFireBurnChecksPerTick", 400);
        VolcanoHeatUtil.tickBurnSweep(world, arena.getCenterX(), arena.getCenterZ(),
                calderaFloorY, maxY, scanRadius, budget, arenaFireBurnCursor, burnChance, random,
                (x, y, z) -> {
                    for (FireTornado tornado : fireTornados) {
                        if (tornado.containsBlock(x, y, z)) {
                            return true;
                        }
                    }
                    return false;
                });
    }

    /** Clears fire left behind after trees burn away (plugin-placed fire does not always vanilla-decay). */
    private void decayOrphanedArenaFire(World world, Arena arena, TerrainPhaseConfig config) {
        double decayChance = config.getDouble("arenaFireDecayChancePerTick", 0.20);
        if (decayChance <= 0) {
            return;
        }
        int scanRadius = Math.max(1, arena.getRadius() - 2);
        int maxY = calderaFloorY + getPeakLayers() + config.getInt("hotNetherrackIgniteHeightAbovePeak", 24);
        int budget = config.getInt("arenaFireDecayChecksPerTick", 500);
        VolcanoHeatUtil.tickFireDecaySweep(world, arena.getCenterX(), arena.getCenterZ(),
                calderaFloorY, maxY, scanRadius, budget, arenaFireDecayCursor, decayChance, random,
                (x, y, z) -> {
                    for (FireTornado tornado : fireTornados) {
                        if (tornado.containsBlock(x, y, z)) {
                            return true;
                        }
                    }
                    return false;
                });
    }

    private void trySpawnProjectiles(World world, TerrainPhaseConfig config) {
        double progress = getVolcanoProgress(config);

        double rateMin = Math.max(0.0, config.getDouble("projectileRatePerSecondMin", 2.0));
        double rateMax = Math.max(rateMin, config.getDouble("projectileRatePerSecondMax", 16.0));
        double exponent = Math.max(0.5, config.getDouble("projectileRateExponent", 2.4));
        double curved = Math.pow(progress, exponent);
        double rate = rateMin <= 0.0
                ? rateMax * curved
                : rateMin * Math.pow(rateMax / Math.max(0.01, rateMin), curved);

        int maxPerTick = Math.max(1, config.getInt("projectileMaxPerTick", 10));
        int maxActive = Math.max(1, config.getInt("maxActiveVolcanoTnt", 28));
        Arena tntArena = activeArena;
        int activeTnt = tntArena == null ? 0 : ArenaEntityUtil.countVolcanoTnt(world, tntArena);
        int room = Math.max(0, maxActive - activeTnt);
        if (room <= 0) {
            tntSpawnCredit = Math.min(tntSpawnCredit, 1.0);
            return;
        }

        // TerrainPhase.onTick runs at 1 Hz (GameManager tick is 20L), so rate is TNT/sec.
        tntSpawnCredit += rate;
        int count = Math.min(maxPerTick, Math.min(room, (int) tntSpawnCredit));
        if (count <= 0) {
            return;
        }
        tntSpawnCredit -= count;
        Location launch = getVentLaunchLocation(world);
        for (int i = 0; i < count; i++) {
            spawnProjectile(world, launch, config, progress);
        }
    }

    /**
     * Combines match time, cone height, and ice/volcano biome dominance so TNT
     * and spread track the same progression as the erupting volcano.
     */
    private double getVolcanoProgress(TerrainPhaseConfig config) {
        double progress = getTntHeightProgress(config);
        if (activeGame != null) {
            double match = MatchPacing.getMatchProgress(activeGame, plugin.getPluginConfig());
            if (match >= 0) {
                progress = Math.max(progress, match);
            }
        }
        VolcanoBiomeWar biomeWar = plugin.getTerrainPhaseManager().getBiomeWar();
        if (biomeWar != null) {
            progress = Math.max(progress, biomeWar.getVolcanicDominance());
        }
        return Math.min(1.0, Math.max(0.0, progress));
    }

    private Location getVentLaunchLocation(World world) {
        double angle = random.nextDouble() * Math.PI * 2;
        double offset = VENT_RADIUS + 0.5 + random.nextDouble() * 1.5;
        int launchY = calderaFloorY + getPeakLayers() + 5;
        return new Location(
                world,
                cx + Math.cos(angle) * offset,
                launchY,
                cz + Math.sin(angle) * offset
        );
    }

    private void spawnProjectile(World world, Location launch, TerrainPhaseConfig config, double progress) {
        Location target = pickTntTarget(world, config, progress);
        int fuse = fuseForFlight(launch, target, config, progress);
        Vector velocity = ballisticVelocity(launch, target, fuse, config, progress);

        TNTPrimed primed = world.spawn(launch, TNTPrimed.class);
        primed.setFuseTicks(fuse);
        primed.setVelocity(velocity);
        ArenaEntityUtil.tagVolcanoTnt(plugin, primed);
    }

    private Location pickTntTarget(World world, TerrainPhaseConfig config, double progress) {
        double minR = Math.max(8.0, config.getDouble("projectileSpreadRadiusMin", 14.0));
        double startR = Math.max(minR, config.getDouble("projectileSpreadRadiusStart", 40.0));
        double endR = config.getDouble("projectileSpreadRadiusEnd", 0.0);
        if (endR <= 0 && activeArena != null) {
            endR = Math.max(startR, activeArena.getRadius() - 5);
        } else if (endR <= 0) {
            endR = 48;
        }
        double spreadExp = Math.max(0.4, config.getDouble("projectileSpreadProgressExponent", 1.35));
        double spreadT = Math.pow(progress, spreadExp);
        double maxR = startR + (endR - startR) * spreadT;
        maxR = Math.max(minR + 8.0, maxR);

        // Area-uniform in the annulus so shots are not clustered at the vent.
        double u = random.nextDouble();
        double dist = Math.sqrt(minR * minR + u * (maxR * maxR - minR * minR));
        double angle = random.nextDouble() * Math.PI * 2;
        double x = cx + Math.cos(angle) * dist + (random.nextDouble() - 0.5) * 6;
        double z = cz + Math.sin(angle) * dist + (random.nextDouble() - 0.5) * 6;
        int gx = (int) Math.floor(x);
        int gz = (int) Math.floor(z);
        int gy = calderaFloorY;
        if (world.isChunkLoaded(gx >> 4, gz >> 4)) {
            gy = VolcanoHeatUtil.findTerrainGroundY(world, gx, gz, calderaFloorY);
        }
        return new Location(world, x, gy + 1, z);
    }

    private int fuseForFlight(Location from, Location to, TerrainPhaseConfig config, double progress) {
        double horiz = Math.hypot(to.getX() - from.getX(), to.getZ() - from.getZ());
        int min = Math.max(20, config.getInt("tntFuseMinTicks", 35));
        int maxStart = Math.max(min, config.getInt("tntFuseMaxTicks", 80));
        int maxEnd = Math.max(maxStart, config.getInt("tntFuseMaxTicksEnd", 170));
        int max = (int) Math.round(maxStart + (maxEnd - maxStart) * progress);
        int travel = 18 + (int) Math.round(horiz / 2.35);
        int fuse = Math.max(min, Math.min(max, travel));
        return fuse + random.nextInt(8);
    }

    private static Vector ballisticVelocity(Location from, Location to, int ticks,
                                            TerrainPhaseConfig config, double progress) {
        int t = Math.max(8, ticks);
        double oneMinusDt = 1.0 - Math.pow(TNT_DRAG, t);
        if (oneMinusDt < 1.0e-6) {
            return to.toVector().subtract(from.toVector()).multiply(0.12);
        }
        double sumDrag = oneMinusDt / (1.0 - TNT_DRAG);
        double dx = to.getX() - from.getX();
        double dy = to.getY() - from.getY();
        double dz = to.getZ() - from.getZ();
        double gravityTerm = (TNT_GRAVITY / (1.0 - TNT_DRAG))
                * (t - TNT_DRAG * oneMinusDt / (1.0 - TNT_DRAG));
        Vector velocity = new Vector(dx / sumDrag, (dy + gravityTerm) / sumDrag, dz / sumDrag);
        double speedStart = Math.max(2.0, config.getDouble("tntMaxSpeedStart", 4.8));
        double speedEnd = Math.max(speedStart, config.getDouble("tntMaxSpeedEnd", 8.4));
        double maxSpeed = speedStart + (speedEnd - speedStart) * progress;
        double speed = velocity.length();
        if (speed > maxSpeed) {
            velocity.multiply(maxSpeed / speed);
        }
        return velocity;
    }

    private void trySpawnMobs(World world, Arena arena, TerrainPhaseConfig config, double intensity) {
        int maxMobs = config.getInt("maxHostileMobs", 6);
        int current = countMobsOfTypes(world, arena, LATE_MOBS);
        if (current >= maxMobs) {
            return;
        }

        double chance = config.getDouble("mobSpawnChancePerTick", 0.05);
        double clamped = Math.min(1.0, intensity / 3.0);
        if (random.nextDouble() > chance * (0.5 + clamped)) {
            return;
        }

        int spawnMin = config.getInt("mobSpawnRadiusMin", 10);
        int spawnMax = Math.max(spawnMin + 1, netherSpread.getConvertedRadius());
        EntityType[] pool = clamped >= 0.55 ? LATE_MOBS : EARLY_MOBS;
        EntityType type = pickWeightedMobType(pool, config);
        if (type == null) {
            return;
        }
        Location spawn = findMobSpawnLocation(world, arena, spawnMin, spawnMax, true);
        if (spawn == null) {
            return;
        }
        LivingEntity mob = (LivingEntity) world.spawnEntity(spawn, type);
        ArenaEntityUtil.tagVolcanoMob(plugin, mob);
    }

    /**
     * Picks from the pool using per-type <NAME>_spawnWeight config keys
     * (default 1.0), so one species can be dialed down without changing the
     * overall spawn cadence. A weight of 0 disables that mob entirely.
     */
    private EntityType pickWeightedMobType(EntityType[] pool, TerrainPhaseConfig config) {
        double totalWeight = 0.0;
        for (EntityType type : pool) {
            totalWeight += getMobSpawnWeight(config, type);
        }
        if (totalWeight <= 0.0) {
            return null;
        }

        double roll = random.nextDouble() * totalWeight;
        for (EntityType type : pool) {
            roll -= getMobSpawnWeight(config, type);
            if (roll <= 0.0) {
                return type;
            }
        }
        return null;
    }

    private double getMobSpawnWeight(TerrainPhaseConfig config, EntityType type) {
        return Math.max(0.0, config.getDouble(type.name() + "_spawnWeight", 1.0));
    }

    private void trySpawnGhasts(World world, Arena arena, TerrainPhaseConfig config, double intensity) {
        int minLayers = config.getInt("ghastMinConeLayers", 5);
        if (builtLayers < minLayers) {
            return;
        }

        double minIntensity = config.getDouble("ghastMinIntensity", 0.12);
        if (intensity < minIntensity) {
            return;
        }

        int maxGhasts = config.getInt("maxGhasts", 4);
        if (volcanoGuardian != null && volcanoGuardian.isActive()
                && config.getInt("volcanoGuardianSuppressGhasts", 1) != 0) {
            maxGhasts = config.getInt("volcanoGuardianMaxGhastsAfterSpawn", 1);
        }
        int currentGhasts = ArenaEntityUtil.countVolcanoMobsOfType(world, arena, EntityType.GHAST);
        if (currentGhasts >= maxGhasts) {
            return;
        }

        double chance = config.getDouble("ghastSpawnChancePerTick", 0.04);
        double layerBonus = Math.min(0.08, builtLayers * 0.004);
        if (random.nextDouble() > chance + layerBonus) {
            return;
        }

        Location spawn = findGhastSpawnLocation(world, arena, config);
        if (spawn == null) {
            return;
        }

        Ghast ghast = world.spawn(spawn, Ghast.class);
        ArenaEntityUtil.tagVolcanoMob(plugin, ghast);
        world.playSound(spawn, Sound.ENTITY_GHAST_AMBIENT, 1.2f, 0.8f);
    }

    private Location findGhastSpawnLocation(World world, Arena arena, TerrainPhaseConfig config) {
        int minRadius = config.getInt("ghastSpawnRadiusMin", 12);
        int maxRadius = Math.max(minRadius + 1, netherSpread.getConvertedRadius());
        int minHeight = config.getInt("ghastMinHeightAboveVent", 5);
        int maxHeight = config.getInt("ghastMaxHeightAboveVent", 16);
        int ventY = calderaFloorY + getPeakLayers();

        for (int attempt = 0; attempt < 20; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double dist = minRadius + random.nextDouble() * Math.max(1, maxRadius - minRadius);
            int x = (int) (cx + Math.cos(angle) * dist);
            int z = (int) (cz + Math.sin(angle) * dist);

            if (!CircleUtil.isInsideCircle(cx, cz, x, z, arena.getRadius() - 2)) {
                continue;
            }
            if (!netherSpread.contains(x, z)) {
                continue;
            }

            int y = ventY + minHeight + random.nextInt(Math.max(1, maxHeight - minHeight + 1));
            if (!hasAerialSpawnSpace(world, x, y, z)) {
                continue;
            }

            Chunk chunk = world.getChunkAt(x >> 4, z >> 4);
            if (!chunk.isLoaded()) {
                chunk.load(true);
            }

            return new Location(world, x + 0.5, y, z + 0.5);
        }
        return null;
    }

    private boolean hasAerialSpawnSpace(World world, int x, int y, int z) {
        for (int dy = 0; dy < 4; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if (!world.getBlockAt(x + dx, y + dy, z + dz).getType().isAir()) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private void trySpawnForestMobs(World world, Arena arena, TerrainPhaseConfig config, double intensity) {
        int maxMobs = config.getInt("maxForestMobs", 6);
        if (maxMobs <= 0) {
            return;
        }
        int current = countMobsOfTypes(world, arena, FOREST_MOBS);
        if (current >= maxMobs) {
            return;
        }

        double chance = config.getDouble("forestMobSpawnChancePerTick", 0.04);
        double clamped = Math.min(1.0, intensity / 3.0);
        if (random.nextDouble() > chance * (0.5 + clamped)) {
            return;
        }

        int netherRadius = netherSpread.getConvertedRadius();
        int spawnMin = Math.max(netherRadius + 2, config.getInt("forestMobSpawnRadiusMin", netherRadius + 4));
        int spawnMax = Math.max(spawnMin + 1, arena.getRadius() - 4);
        if (spawnMin >= arena.getRadius() - 3) {
            return;
        }
        EntityType type = FOREST_MOBS[random.nextInt(FOREST_MOBS.length)];
        Location spawn = findMobSpawnLocation(world, arena, spawnMin, spawnMax, false);
        if (spawn == null) {
            return;
        }
        LivingEntity mob = (LivingEntity) world.spawnEntity(spawn, type);
        ArenaEntityUtil.tagPhaseMob(plugin, mob);
    }

    private int countMobsOfTypes(World world, Arena arena, EntityType[] types) {
        int total = 0;
        for (EntityType type : types) {
            total += ArenaEntityUtil.countVolcanoMobsOfType(world, arena, type);
        }
        return total;
    }

    private Location findMobSpawnLocation(World world, Arena arena, int minRadius, int maxRadius,
                                          boolean netherGround) {
        for (int attempt = 0; attempt < 16; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double dist = minRadius + random.nextDouble() * Math.max(1, maxRadius - minRadius);
            int x = (int) (cx + Math.cos(angle) * dist);
            int z = (int) (cz + Math.sin(angle) * dist);
            if (!CircleUtil.isInsideCircle(cx, cz, x, z, arena.getRadius() - 2)) {
                continue;
            }
            if (netherGround != netherSpread.isConvertedGround(world, x, z)) {
                continue;
            }
            int y = findSurfaceY(world, x, (int) arena.getCenterY(), z);
            Block block = world.getBlockAt(x, y, z);
            Block above = world.getBlockAt(x, y + 1, z);
            if (block.getType().isSolid() && !block.isLiquid() && above.getType().isAir()) {
                return new Location(world, x + 0.5, y + 1, z + 0.5);
            }
        }
        return null;
    }

    private void tryVolcanoLightning(World world, TerrainPhaseConfig config, double intensity) {
        double minIntensity = config.getDouble("volcanoLightningMinIntensity", 0.2);
        if (intensity < minIntensity) {
            return;
        }

        long now = System.currentTimeMillis();
        long minInterval = config.getInt("volcanoLightningMinIntervalMs", 12000);
        long maxInterval = config.getInt("volcanoLightningMaxIntervalMs", 35000);
        double normalized = Math.min(1.0, intensity / 3.0);
        long interval = (long) (maxInterval - (maxInterval - minInterval) * normalized);
        if (now - lastVolcanoLightningMs < interval) {
            return;
        }
        if (random.nextDouble() > 0.25 + normalized * 0.55) {
            return;
        }

        lastVolcanoLightningMs = now;
        Location vent = new Location(world, cx, calderaFloorY + getPeakLayers() + 2.5, cz);
        Chunk chunk = world.getChunkAt(vent.getBlockX() >> 4, vent.getBlockZ() >> 4);
        if (!chunk.isLoaded()) {
            chunk.load(true);
        }

        ParticleUtil.spawn(world, Particle.ELECTRIC_SPARK, vent,
                12 + (int) (normalized * 16), 0.4, 0.8, 0.4, 0.05);
        world.playSound(vent, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.6f + (float) normalized * 0.7f, 0.8f);
        world.playSound(vent, Sound.ENTITY_LIGHTNING_BOLT_IMPACT, 0.5f + (float) normalized * 0.5f, 1.0f);

        double realThreshold = config.getDouble("volcanoLightningRealStrikeThreshold", 0.65);
        if (normalized >= realThreshold) {
            world.strikeLightning(vent);
        } else {
            world.strikeLightningEffect(vent);
        }
    }

    private void emitCalderaSmoke(World world, double intensity) {
        double clamped = Math.min(1.0, intensity);
        int ventTopY = calderaFloorY + getPeakLayers() + 1;

        // Steady plume rising from the vent; thickens as the eruption intensifies.
        int plumes = 3 + (int) (clamped * 5);
        for (int i = 0; i < plumes; i++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double dist = random.nextDouble() * VENT_RADIUS;
            double x = cx + Math.cos(angle) * dist;
            double z = cz + Math.sin(angle) * dist;
            ParticleUtil.spawnForced(world, Particle.CAMPFIRE_SIGNAL_SMOKE,
                    x, ventTopY + random.nextDouble() * 2, z,
                    1, 0.3, 0.4, 0.3, 0.012);
        }

        // Wisps drifting off the caldera bowl itself.
        int wisps = 2 + random.nextInt(3);
        for (int i = 0; i < wisps; i++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double dist = random.nextDouble() * VolcanoCaldera.CALDERA_LAVA_RADIUS;
            double x = cx + Math.cos(angle) * dist;
            double z = cz + Math.sin(angle) * dist;
            ParticleUtil.spawnForced(world, Particle.CAMPFIRE_COSY_SMOKE,
                    x, calderaFloorY + 1.2, z,
                    1, 0.25, 0.2, 0.25, 0.008);
        }

        ParticleUtil.spawnForced(world, Particle.LARGE_SMOKE,
                cx, ventTopY + 1, cz,
                4 + (int) (clamped * 8), 1.2, 1.5, 1.2, 0.02);
    }

    private void playEffects(World world, double intensity) {
        double clamped = Math.min(1.0, intensity);
        if (random.nextDouble() > 0.3 + clamped * 0.5) {
            return;
        }
        Location center = new Location(world, cx, calderaFloorY + getPeakLayers() + 1, cz);
        int particleCount = 3 + (int) (clamped * 12);
        ParticleUtil.spawn(world, Particle.LAVA, center, particleCount, 2, 1, 2, 0.02);
        ParticleUtil.spawn(world, Particle.SMOKE, center.clone().add(0, 2, 0), particleCount, 1.5, 2, 1.5, 0.05);
        ParticleUtil.spawn(world, Particle.FLAME, center.clone().add(0, 1, 0), particleCount / 2, 1, 1, 1, 0.03);

        if (clamped > 0.5 && random.nextDouble() < clamped * 0.3) {
            world.playSound(center, Sound.ENTITY_GHAST_SHOOT, 0.6f + (float) clamped * 0.4f, 0.8f);
        } else if (random.nextDouble() < 0.4) {
            world.playSound(center, Sound.BLOCK_LAVA_POP, 0.8f, 0.9f);
        }
    }

    private int findSurfaceY(World world, int x, int baseY, int z) {
        for (int y = baseY + 25; y >= 1; y--) {
            Material type = world.getBlockAt(x, y, z).getType();
            if (type.isSolid() && type != Material.LAVA) {
                return y;
            }
        }
        return baseY;
    }

    public boolean isVolcanoGuardianActive() {
        return volcanoGuardian != null && volcanoGuardian.isActive();
    }

    public VolcanoPiglinHorde getPiglinHorde() {
        return piglinHorde;
    }

    public Location rollUltimatePowerUpLocation(PowerUpDefinition def) {
        if (activeArena == null) {
            return null;
        }
        double minRadius = def.getDouble("spawnRadiusMin", VolcanoCaldera.RIM_RADIUS + 5);
        double maxRadius = def.getDouble("spawnRadiusMax", 28);
        return ArenaScatterUtil.findCalderaLocation(
                activeArena, cx, cz, calderaFloorY, minRadius, maxRadius, plugin.getArenaGenerator());
    }

    public void pauseAiGuardianForPlayer() {
        guardianPausedForPlayer = true;
        if (volcanoGuardian != null) {
            volcanoGuardian.destroy();
            volcanoGuardian = null;
        }
    }

    public void restoreAiGuardianIfNeeded() {
        guardianPausedForPlayer = false;
        if (volcanoGuardian != null && volcanoGuardian.isActive()) {
            return;
        }
        if (!volcanoGuardianSpawned || activeArena == null || activeConfig == null) {
            return;
        }
        World world = activeArena.getWorld();
        if (world == null) {
            return;
        }
        if (activeGame == null) {
            return;
        }
        int peakY = calderaFloorY + getPeakLayers();
        volcanoGuardian = new VolcanoGuardian(plugin);
        volcanoGuardian.configure(activeConfig);
        volcanoGuardian.spawn(activeGame, activeArena, cx, cz, calderaFloorY, peakY);
        if (!volcanoGuardian.isActive()) {
            volcanoGuardian = null;
        }
    }
}
