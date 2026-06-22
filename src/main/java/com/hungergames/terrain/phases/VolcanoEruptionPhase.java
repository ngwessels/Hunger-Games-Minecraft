package com.hungergames.terrain.phases;

import com.hungergames.HungerGamesPlugin;
import com.hungergames.arena.Arena;
import com.hungergames.arena.VolcanoCaldera;
import com.hungergames.game.Game;
import com.hungergames.game.MatchPacing;
import com.hungergames.gamemode.TerrainPhaseConfig;
import com.hungergames.powerup.PowerUpDefinition;
import com.hungergames.terrain.FireTornado;
import com.hungergames.terrain.TerrainPhase;
import com.hungergames.terrain.VolcanoGuardian;
import com.hungergames.terrain.VolcanoHeatUtil;
import com.hungergames.terrain.VolcanoLavaFlow;
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
    /**
     * Layers whose radius barely exceeds the caldera bowl would only place thin
     * sliver rings (stacked, they look like netherrack pillars around the rim).
     */
    private static final double MIN_CONE_LAYER_RADIUS = VolcanoCaldera.CALDERA_LAVA_RADIUS + 2.0;
    /** Hard cap on cone block reads per tick so big cones can't stall the main thread. */
    private static final int CONE_VISIT_BUDGET_PER_TICK = 60_000;
    private static final EntityType[] EARLY_MOBS = {EntityType.BLAZE, EntityType.MAGMA_CUBE};
    private static final EntityType[] LATE_MOBS = {
            EntityType.BLAZE, EntityType.MAGMA_CUBE,
            EntityType.WITHER_SKELETON, EntityType.PIGLIN_BRUTE, EntityType.HOGLIN
    };
    /** Original arena terrain that must be replaced inside the growing cone shell. */
    private static final Set<Material> NATURAL_TERRAIN = Set.of(
            Material.GRASS_BLOCK, Material.DIRT, Material.COARSE_DIRT, Material.PODZOL,
            Material.STONE, Material.BASALT, Material.SMOOTH_BASALT, Material.COBBLESTONE,
            Material.TUFF, Material.DEEPSLATE, Material.GRAVEL, Material.SAND, Material.SANDSTONE,
            Material.OAK_LOG, Material.OAK_LEAVES, Material.SPRUCE_LOG, Material.SPRUCE_LEAVES,
            Material.BIRCH_LOG, Material.BIRCH_LEAVES,
            Material.TALL_GRASS, Material.SHORT_GRASS, Material.DEAD_BUSH, Material.FIRE
    );

    private final HungerGamesPlugin plugin;
    private final Random random = new Random();

    private long eruptionStartMs;
    private long lastProjectileMs;
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
    private TerrainPhaseConfig activeConfig;
    private Arena activeArena;
    private Game activeGame;
    private final int[] hotRockIgniteCursor = new int[1];
    private final int[] arenaFireBurnCursor = new int[1];
    private final int[] arenaFireDecayCursor = new int[1];
    private boolean calderaRimOpened;

    public VolcanoEruptionPhase(HungerGamesPlugin plugin) {
        this.plugin = plugin;
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
        lastProjectileMs = 0;
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

        World world = arena.getWorld();
        if (world != null) {
            calderaFloorY = findSurfaceY(world, (int) cx, (int) arena.getCenterY(), (int) cz);
            VolcanoCaldera.activateCalderaLava(world, cx, calderaFloorY + 1, cz);
            lavaFlow = new VolcanoLavaFlow();
            lavaFlow.initialize(world, cx, cz, calderaFloorY, random);
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

            if (intensity >= 0.1) {
                trySpawnProjectiles(world, config);
            }
        }

        if (intensity >= config.getDouble("mobSpawnMinIntensity", 0.25)) {
            trySpawnMobs(world, arena, config, intensity);
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

    private int getMaxConeBaseRadius(Arena arena, TerrainPhaseConfig config) {
        int arenaEdge = Math.max(1, arena.getRadius() - CONE_ARENA_EDGE_MARGIN);
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
        int maxBase = getMaxConeBaseRadius(arena, config);
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
            if (layerRadius < MIN_CONE_LAYER_RADIUS) {
                if (knownCompleteLayers == buildScanLayer - 1) {
                    knownCompleteLayers = buildScanLayer;
                }
                if (!advanceScanLayer(targetLayers) && ++wraps >= 2) {
                    break;
                }
                continue;
            }
            int[] result = scanAndFillLayer(world, buildScanLayer, layerRadius, tipRadius,
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

        // Monotonic: the netherrack peak never shrinks (TNT can't break it), so
        // keep launch/spawn heights stable while rims are being backfilled.
        builtLayers = Math.max(builtLayers, Math.max(1, Math.min(targetLayers, knownCompleteLayers + 1)));
        carveVent(world, targetLayers + 4);
        if (!calderaRimOpened && builtLayers >= 1) {
            openCalderaRimForOverflow(world);
            calderaRimOpened = true;
        }
        maintainSummitLavaPool(world, game, arena, config);
        spillLavaDownInteriorWalls(world, game, arena, config);
    }

    /** Lava surface sits one block below the physical cone peak. */
    private int getSummitLavaY() {
        return calderaFloorY + Math.max(1, getPeakLayers() - 1);
    }

    /**
     * Summit pool radius grows with the cone so lava reaches the inner shell and can
     * spill down the slopes instead of staying trapped in the original caldera bowl.
     */
    private int getSummitPoolRadius(Game game, Arena arena, TerrainPhaseConfig config) {
        long eruptionElapsedSec = (System.currentTimeMillis() - eruptionStartMs) / 1000L;
        int targetLayers = getTargetLayers(game, config, eruptionElapsedSec);
        int targetBaseRadius = getTargetBaseRadius(arena, game, config, eruptionElapsedSec);
        double tipRadius = config.getDouble("coneTipRadius", 1.5);
        int layer = Math.max(1, getPeakLayers());
        double coneRadius = coneRadiusAtLayer(layer, targetLayers, targetBaseRadius, tipRadius);
        int poolRadius = (int) Math.ceil(Math.max(VolcanoCaldera.CALDERA_LAVA_RADIUS, coneRadius));
        if (coneRadius < MIN_CONE_LAYER_RADIUS) {
            poolRadius = Math.max(poolRadius, (int) MIN_CONE_LAYER_RADIUS);
        }
        return poolRadius;
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

    /**
     * Keeps a lava pool in the crater one block below the volcano's current peak so the
     * summit never looks like an empty shaft. The pool rides up as layers complete and
     * widens to the cone lip; only the central vent shaft is cleared below the pool so
     * flowing lava on the interior slopes is left alone.
     */
    private void maintainSummitLavaPool(World world, Game game, Arena arena, TerrainPhaseConfig config) {
        int poolY = getSummitLavaY();
        int plugY = poolY - 1;
        int icx = (int) cx;
        int icz = (int) cz;
        int r = getSummitPoolRadius(game, arena, config);
        int rSq = r * r;

        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (dx * dx + dz * dz > rSq) {
                    continue;
                }
                int x = icx + dx;
                int z = icz + dz;

                Block plug = world.getBlockAt(x, plugY, z);
                if (plug.getType() != Material.MAGMA_BLOCK && plug.getType() != Material.LAVA) {
                    plug.setType(Material.MAGMA_BLOCK, false);
                }
                Block pool = world.getBlockAt(x, poolY, z);
                if (!VolcanoCaldera.isLavaSource(pool)) {
                    VolcanoCaldera.placeLavaSource(pool);
                }
            }
        }
        clearVentBelowPool(world);
    }

    /** Clears only the narrow vent column under the pool; does not strip lava on cone slopes. */
    private void clearVentBelowPool(World world) {
        int plugY = getSummitLavaY() - 1;
        int icx = (int) cx;
        int icz = (int) cz;
        for (int y = plugY - 1; y >= calderaFloorY + 1; y--) {
            for (int dx = -VENT_RADIUS; dx <= VENT_RADIUS; dx++) {
                for (int dz = -VENT_RADIUS; dz <= VENT_RADIUS; dz++) {
                    if (!isInsideVent(dx, dz)) {
                        continue;
                    }
                    Block block = world.getBlockAt(icx + dx, y, icz + dz);
                    Material type = block.getType();
                    if (type == Material.LAVA || type == Material.MAGMA_BLOCK) {
                        block.setType(Material.AIR, false);
                    }
                }
            }
        }
    }

    /**
     * Seeds lava on the inner cone face so molten rock visibly runs down the volcano as
     * it grows, even when the summit pool sits above the widest part of the shell.
     */
    private void spillLavaDownInteriorWalls(World world, Game game, Arena arena, TerrainPhaseConfig config) {
        int budget = config.getInt("lavaConeSpillPerTick", 8);
        if (budget <= 0 || getPeakLayers() < 1) {
            return;
        }

        long eruptionElapsedSec = (System.currentTimeMillis() - eruptionStartMs) / 1000L;
        int targetLayers = getTargetLayers(game, config, eruptionElapsedSec);
        int targetBaseRadius = getTargetBaseRadius(arena, game, config, eruptionElapsedSec);
        double tipRadius = config.getDouble("coneTipRadius", 1.5);
        int poolY = getSummitLavaY();
        int heightRange = Math.max(1, poolY - calderaFloorY);
        int placed = 0;

        for (int attempt = 0; attempt < budget * 4 && placed < budget; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2;
            int y = calderaFloorY + 1 + random.nextInt(heightRange);
            int layer = y - calderaFloorY;
            double layerRadius = coneRadiusAtLayer(layer, targetLayers, targetBaseRadius, tipRadius);
            if (layerRadius < MIN_CONE_LAYER_RADIUS) {
                continue;
            }
            int dist = Math.max((int) MIN_CONE_LAYER_RADIUS - 1, (int) Math.round(layerRadius) - 1);
            int x = (int) Math.round(cx + Math.cos(angle) * dist);
            int z = (int) Math.round(cz + Math.sin(angle) * dist);

            if (tryPlaceSpillLava(world, x, y, z, angle)) {
                placed++;
            }
        }
    }

    private boolean tryPlaceSpillLava(World world, int x, int y, int z, double outwardAngle) {
        Block face = world.getBlockAt(x, y, z);
        Material faceType = face.getType();
        if (!VolcanoHeatUtil.isHotVolcanoBlock(faceType) && faceType != Material.BLACKSTONE) {
            return false;
        }

        Block above = world.getBlockAt(x, y + 1, z);
        if (above.getType().isAir()) {
            placeFlowingLava(above);
            return true;
        }

        int ox = (int) Math.round(Math.cos(outwardAngle));
        int oz = (int) Math.round(Math.sin(outwardAngle));
        if (ox == 0 && oz == 0) {
            return false;
        }
        Block outer = world.getBlockAt(x + ox, y, z + oz);
        if (outer.getType().isAir()) {
            placeFlowingLava(outer);
            return true;
        }
        Block outerAbove = world.getBlockAt(x + ox, y + 1, z + oz);
        if (outerAbove.getType().isAir()) {
            placeFlowingLava(outerAbove);
            return true;
        }
        return false;
    }

    private static void placeFlowingLava(Block block) {
        var data = Material.LAVA.createBlockData();
        if (data instanceof org.bukkit.block.data.Levelled levelled) {
            levelled.setLevel(1);
            block.setBlockData(levelled, true);
        } else {
            block.setType(Material.LAVA, true);
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
     * Single bounded pass over one cone layer: fills missing blocks while counting work.
     * Returns {placed, visited, missingUnfilled, finishedScan(0/1), resumeIndex}.
     */
    private int[] scanAndFillLayer(World world, int layer, double radius, double tipRadius,
                                   int placeBudget, int visitBudget, int startIndex) {
        int icx = (int) cx;
        int icz = (int) cz;
        int ir = (int) Math.ceil(radius);
        int side = 2 * ir + 1;
        int totalCells = side * side;
        double radiusSq = radius * radius;
        double magmaRadiusSq = (tipRadius + 1) * (tipRadius + 1);
        boolean magmaLayer = layer % 8 == 0;
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
            if (isInsideVent(dx, dz) || isInsideCalderaBowl(dx, dz)) {
                continue;
            }
            int x = icx + dx;
            int z = icz + dz;
            Block block = world.getBlockAt(x, y, z);
            Material type = block.getType();
            Material mat = magmaLayer && distSq <= magmaRadiusSq
                    ? Material.MAGMA_BLOCK : Material.NETHERRACK;
            if (type == mat) {
                continue;
            }
            if (!shouldReplaceForCone(type)) {
                continue;
            }

            int solidTopY = findSolidTopY(world, x, z, y);
            if (y - 1 > solidTopY) {
                int gapPlaced = fillColumnGaps(world, x, z, solidTopY + 1, y - 1, mat, placeBudget - placed);
                placed += gapPlaced;
                if (placed >= placeBudget) {
                    missing++;
                    continue;
                }
            }

            Material below = world.getBlockAt(x, y - 1, z).getType();
            if (!below.isSolid()) {
                missing++;
                continue;
            }
            if (placed >= placeBudget) {
                missing++;
                continue;
            }
            block.setType(mat, false);
            if (VolcanoHeatUtil.isHotVolcanoBlock(mat)) {
                VolcanoHeatUtil.igniteFlammablesNearHotBlock(world, x, y, z);
            }
            placed++;
        }

        return new int[]{placed, visited, missing, index >= totalCells ? 1 : 0, index};
    }

    private boolean shouldReplaceForCone(Material type) {
        return type == Material.AIR || type == Material.LAVA || isNaturalTerrain(type)
                || type == Material.NETHERRACK || type == Material.MAGMA_BLOCK || type == Material.BLACKSTONE;
    }

    private boolean isNaturalTerrain(Material type) {
        return NATURAL_TERRAIN.contains(type);
    }

    /** Highest solid block below the target layer (terrain dip or existing cone). */
    private int findSolidTopY(World world, int x, int z, int belowY) {
        for (int y = belowY - 1; y >= 1; y--) {
            Material type = world.getBlockAt(x, y, z).getType();
            if (type.isSolid() && type != Material.LAVA) {
                return y;
            }
        }
        return calderaFloorY;
    }

    private int fillColumnGaps(World world, int x, int z, int fromY, int toY, Material mat, int budget) {
        int placed = 0;
        for (int fillY = fromY; fillY <= toY && placed < budget; fillY++) {
            Block block = world.getBlockAt(x, fillY, z);
            Material type = block.getType();
            if (type == mat) {
                continue;
            }
            if (type.isAir() || type == Material.LAVA || isNaturalTerrain(type)
                    || type == Material.NETHERRACK || type == Material.MAGMA_BLOCK || type == Material.BLACKSTONE) {
                block.setType(mat, false);
                if (VolcanoHeatUtil.isHotVolcanoBlock(mat)) {
                    VolcanoHeatUtil.igniteFlammablesNearHotBlock(world, x, fillY, z);
                }
                placed++;
            }
        }
        return placed;
    }

    /** Keeps wood and leaves burning as hot netherrack expands into the forest. */
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

    private boolean isInsideVent(int dx, int dz) {
        return dx * dx + dz * dz <= VENT_RADIUS * VENT_RADIUS;
    }

    private boolean isInsideCalderaBowl(int dx, int dz) {
        return dx * dx + dz * dz <= VolcanoCaldera.CALDERA_LAVA_RADIUS * VolcanoCaldera.CALDERA_LAVA_RADIUS;
    }

    private void carveVent(World world, int topY) {
        int icx = (int) cx;
        int icz = (int) cz;
        int poolY = getSummitLavaY();
        int plugY = poolY - 1;
        for (int y = calderaFloorY + 1; y <= calderaFloorY + topY; y++) {
            if (y == poolY || y == plugY) {
                continue; // summit lava pool lives here
            }
            for (int dx = -VENT_RADIUS; dx <= VENT_RADIUS; dx++) {
                for (int dz = -VENT_RADIUS; dz <= VENT_RADIUS; dz++) {
                    if (!isInsideVent(dx, dz)) {
                        continue;
                    }
                    Block block = world.getBlockAt(icx + dx, y, icz + dz);
                    Material type = block.getType();
                    if (y > calderaFloorY && (type == Material.LAVA || type == Material.MAGMA_BLOCK)) {
                        block.setType(Material.AIR, false);
                    } else if (type != Material.LAVA && type != Material.MAGMA_BLOCK) {
                        block.setType(Material.AIR, false);
                    }
                }
            }
        }
    }

    private void trySpawnProjectiles(World world, TerrainPhaseConfig config) {
        long now = System.currentTimeMillis();
        double intervalMin = config.getDouble("projectileIntervalMin", 4) * 1000;
        double intervalMax = config.getDouble("projectileIntervalMax", 1) * 1000;
        double heightProgress = getTntHeightProgress(config);
        long interval = (long) (intervalMax + (intervalMin - intervalMax) * (1.0 - heightProgress));

        if (now - lastProjectileMs < interval) {
            return;
        }
        lastProjectileMs = now;

        int maxBurst = Math.max(1, config.getInt("projectileMaxPerBurst", 4));
        int count = 1 + (int) Math.round(heightProgress * (maxBurst - 1));
        for (int i = 0; i < count; i++) {
            spawnProjectile(world, getVentLaunchLocation(world), config);
        }
    }

    private Location getVentLaunchLocation(World world) {
        double angle = random.nextDouble() * Math.PI * 2;
        double offset = VENT_RADIUS + 0.5 + random.nextDouble() * 1.5;
        int launchY = calderaFloorY + getPeakLayers() + 3;
        return new Location(
                world,
                cx + Math.cos(angle) * offset,
                launchY,
                cz + Math.sin(angle) * offset
        );
    }

    private void spawnProjectile(World world, Location launch, TerrainPhaseConfig config) {
        double speedMin = config.getDouble("tntSpeedMin", 0.4);
        double speedMax = config.getDouble("tntSpeedMax", 1.8);
        double upMin = config.getDouble("tntUpMin", 0.6);
        double upMax = config.getDouble("tntUpMax", 2.0);
        double distancePerLayer = config.getDouble("tntDistancePerLayer", 0.04);
        double maxSpeed = config.getDouble("tntMaxSpeed", 3.5);

        double layerScale = 1.0 + builtLayers * distancePerLayer;
        double speed = (speedMin + random.nextDouble() * (speedMax - speedMin)) * layerScale;
        double up = (upMin + random.nextDouble() * (upMax - upMin)) * (1.0 + builtLayers * 0.02);
        speed = Math.min(maxSpeed, speed);

        double angle = random.nextDouble() * Math.PI * 2;
        Vector velocity = new Vector(Math.cos(angle) * speed, up, Math.sin(angle) * speed);

        int fuseMin = config.getInt("tntFuseMinTicks", 70);
        int fuseMax = config.getInt("tntFuseMaxTicks", 160);
        if (fuseMax < fuseMin) {
            fuseMax = fuseMin;
        }
        int fuse = fuseMin + random.nextInt(fuseMax - fuseMin + 1);
        fuse += builtLayers / 2;

        TNTPrimed primed = world.spawn(launch, TNTPrimed.class);
        primed.setFuseTicks(fuse);
        primed.setVelocity(velocity);
        ArenaEntityUtil.tagVolcanoTnt(plugin, primed);
    }

    private void trySpawnMobs(World world, Arena arena, TerrainPhaseConfig config, double intensity) {
        int maxMobs = config.getInt("maxHostileMobs", 6);
        int current = ArenaEntityUtil.countVolcanoMobs(world, arena);
        if (current >= maxMobs) {
            return;
        }

        double chance = config.getDouble("mobSpawnChancePerTick", 0.05);
        double clamped = Math.min(1.0, intensity / 3.0);
        if (random.nextDouble() > chance * (0.5 + clamped)) {
            return;
        }

        int spawnMin = config.getInt("mobSpawnRadiusMin", 10);
        int spawnMax = config.getInt("mobSpawnRadiusMax", 35);
        EntityType[] pool = clamped >= 0.55 ? LATE_MOBS : EARLY_MOBS;
        EntityType type = pool[random.nextInt(pool.length)];
        Location spawn = findMobSpawnLocation(world, arena, spawnMin, spawnMax);
        if (spawn == null) {
            return;
        }
        LivingEntity mob = (LivingEntity) world.spawnEntity(spawn, type);
        ArenaEntityUtil.tagVolcanoMob(plugin, mob);
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
        int maxRadius = config.getInt("ghastSpawnRadiusMax", 40);
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

    private Location findMobSpawnLocation(World world, Arena arena, int minRadius, int maxRadius) {
        for (int attempt = 0; attempt < 12; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double dist = minRadius + random.nextDouble() * (maxRadius - minRadius);
            int x = (int) (cx + Math.cos(angle) * dist);
            int z = (int) (cz + Math.sin(angle) * dist);
            if (!CircleUtil.isInsideCircle(cx, cz, x, z, arena.getRadius() - 2)) {
                continue;
            }
            int y = findSurfaceY(world, x, (int) arena.getCenterY(), z);
            Block block = world.getBlockAt(x, y, z);
            if (block.getType().isSolid()) {
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
