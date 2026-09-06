package com.hungergames.arena;

import com.hungergames.HungerGamesPlugin;
import com.hungergames.gamemode.GameMode;
import com.hungergames.gamemode.TreePlacementConfig;
import com.hungergames.util.BlockBatchUtil;
import com.hungergames.util.CircleUtil;
import com.hungergames.util.NoiseUtil;
import com.hungergames.util.SpawnLocationUtil;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class ArenaGenerator {

    /** Clear up to this height when (re)generating; must exceed max volcano cone peak. */
    private static final int CLEAR_HEIGHT = 125;
    private final HungerGamesPlugin plugin;

    public ArenaGenerator(HungerGamesPlugin plugin) {
        this.plugin = plugin;
    }

    private void logSettlements(Arena arena, GameMode gameMode) {
        if (!Settlements.isActive(arena, gameMode)) {
            return;
        }
        SettlementLayout layout = arena.getSettlementLayout();
        long chests = layout.getChestSites().size();
        plugin.getLogger().info("Settlements for arena " + arena.getName() + ": "
                + layout.getMobSites().size() + " mob site(s), " + chests
                + " chest(s), " + layout.getChurches().size() + " church(es).");
    }

    public void generate(Arena arena, GameMode gameMode, Player player, Runnable onComplete) {
        arena.setRadius(gameMode.getArenaRadius());
        World world = plugin.getArenaManager().getOrCreateWorld(arena);
        if (world == null) {
            if (player != null) {
                player.sendMessage(plugin.formatMessage("&cFailed to create arena world."));
            }
            return;
        }
        rebuild(arena, gameMode, player, world, "Generating arena...", "&agenerated!", onComplete);
    }

    public void reset(Arena arena, GameMode gameMode, Player player, Runnable onComplete) {
        restoreOrRebuild(arena, gameMode, player, "&areset!", onComplete);
    }

    public void regenerateInterior(Arena arena, GameMode gameMode, Consumer<Double> progress, Runnable onComplete) {
        restoreOrRebuild(arena, gameMode, null, null, onComplete);
    }

    private void restoreOrRebuild(Arena arena, GameMode gameMode, Player player,
                                  String successVerb, Runnable onComplete) {
        if (plugin.getArenaManager().hasTemplate(arena)) {
            if (player != null) {
                player.sendMessage(plugin.formatMessage("&7Restoring arena from saved copy..."));
            }
            plugin.getArenaManager().restoreFromTemplate(arena, success -> {
                if (!success) {
                    plugin.getLogger().warning("Template restore failed for " + arena.getName()
                            + "; rebuilding terrain.");
                    rebuildFromScratch(arena, gameMode, player, successVerb, onComplete);
                    return;
                }
                finishRestoredWorld(arena, gameMode, player, successVerb, onComplete);
            });
            return;
        }
        if (player != null) {
            player.sendMessage(plugin.formatMessage(
                    "&7No saved copy yet — rebuilding, then saving a copy for next time."));
        }
        rebuildFromScratch(arena, gameMode, player, successVerb, onComplete);
    }

    private void rebuildFromScratch(Arena arena, GameMode gameMode, Player player,
                                    String successVerb, Runnable onComplete) {
        World world = plugin.getArenaManager().getOrCreateWorld(arena);
        if (world == null) {
            if (player != null) {
                player.sendMessage(plugin.formatMessage("&cFailed to load arena world."));
            }
            if (onComplete != null) {
                onComplete.run();
            }
            return;
        }
        String verb = successVerb != null ? successVerb : "&areset!";
        rebuild(arena, gameMode, player, world, "Resetting arena...", verb, onComplete);
    }

    private void finishRestoredWorld(Arena arena, GameMode gameMode, Player player,
                                     String successVerb, Runnable onComplete) {
        World world = arena.getWorld();
        if (world != null) {
            setupWorldBorder(world, arena);
            Settlements.refreshLayout(arena, gameMode);
            ArcticRing.refreshLayout(arena, gameMode);
            plugin.getArenaManager().save();
        }
        if (player != null && successVerb != null) {
            player.sendMessage(plugin.formatMessage("&aArena &e" + arena.getName() + " " + successVerb));
        }
        if (onComplete != null) {
            onComplete.run();
        }
    }

    private void rebuild(Arena arena, GameMode gameMode, Player player, World world,
                         String bossBarLabel, String successVerb, Runnable onComplete) {
        int baseY = (int) arena.getCenterY();
        int radius = arena.getRadius();
        int wallHeight = arena.getWallHeight();
        int wallThickness = plugin.getPluginConfig().getWallThickness();
        double cx = arena.getCenterX();
        double cz = arena.getCenterZ();
        int seed = arena.getTerrainSeed();

        BossBar bossBar = null;
        if (player != null) {
            bossBar = plugin.getServer().createBossBar(bossBarLabel, BarColor.GREEN, BarStyle.SOLID);
            bossBar.addPlayer(player);
        }
        final BossBar progressBar = bossBar;

        List<BlockBatchUtil.BlockChange> changes = BlockBatchUtil.newChangeList();

        int minX = (int) (cx - radius - wallThickness - 5);
        int maxX = (int) (cx + radius + wallThickness + 5);
        int minZ = (int) (cz - radius - wallThickness - 5);
        int maxZ = (int) (cz + radius + wallThickness + 5);

        int forestRadius = gameMode.getForestRadius();
        int blendWidth = gameMode.getArctic() != null && gameMode.getArctic().isEnabled()
                ? gameMode.getArctic().getBlendWidth() : 0;
        int[][] heightmap = TerrainHeightmap.generate(cx, cz, radius, minX, maxX, minZ, maxZ,
                baseY, seed, gameMode.getInitialTerrain(), forestRadius, blendWidth);

        double[] groveArea = JungleGrove.groveArea(arena, gameMode);
        double[] settlementSites = Settlements.isActive(arena, gameMode)
                ? Settlements.siteCoordinates(Settlements.planSites(arena, gameMode))
                : null;
        List<VolcanoPonds.Pond> ponds = VolcanoPonds.plan(arena, gameMode, groveArea,
                settlementSites, heightmap, minX, minZ);

        clearInteriorAboveSurface(changes, heightmap, minX, maxX, minZ, maxZ, cx, cz, radius);

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                boolean inside = CircleUtil.isInsideCircle(cx, cz, x, z, radius);
                boolean inWall = CircleUtil.isInsideAnnulus(cx, cz, x, z, radius, radius + wallThickness);

                if (inside || inWall) {
                    changes.add(new BlockBatchUtil.BlockChange(x, 0, z, Material.BEDROCK));
                }

                if (inside) {
                    buildInteriorColumn(changes, arena, gameMode, groveArea, settlementSites, ponds,
                            heightmap, minX, minZ, x, z, seed, cx, cz, forestRadius);
                }

                if (inWall) {
                    int groundY = TerrainHeightmap.getHeight(heightmap, minX, minZ, x, z);
                    int wallTop = groundY + wallHeight;
                    for (int y = 1; y <= wallTop; y++) {
                        changes.add(new BlockBatchUtil.BlockChange(x, y, z, Material.OBSIDIAN));
                    }
                    changes.add(new BlockBatchUtil.BlockChange(x, wallTop + 1, z, Material.BEDROCK));
                    changes.add(new BlockBatchUtil.BlockChange(x, wallTop + 2, z, Material.BEDROCK));
                }
            }
        }

        int centerSurface = TerrainHeightmap.getHeight(heightmap, minX, minZ, (int) cx, (int) cz);
        addCenterFeature(changes, gameMode, cx, centerSurface, cz);
        plantInteriorTrees(changes, arena, gameMode, groveArea, settlementSites, ponds, heightmap,
                minX, maxX, minZ, maxZ, seed, cx, cz, forestRadius);
        VolcanoPonds.add(changes, arena, gameMode, heightmap, minX, minZ, ponds);
        JungleGrove.addJungleGrove(changes, arena, gameMode, heightmap, minX, minZ);
        Settlements.addBuildings(changes, arena, gameMode, heightmap, minX, minZ,
                arena.getSettlementLayout());
        ArcticRing.add(changes, arena, gameMode, heightmap, minX, minZ);
        logSettlements(arena, gameMode);

        BlockBatchUtil.applyBatched(plugin, world, changes, plugin.getPluginConfig().getBlocksPerTick(),
                progress -> {
                    if (progressBar != null) {
                        progressBar.setProgress(Math.min(1.0, progress));
                    }
                },
                () -> {
                    applyBiomes(world, arena, gameMode);
                    setupWorldBorder(world, arena);
                    List<Location> spawns = computeSpawnRing(world, arena, gameMode);
                    arena.setSpawnPoints(spawns);
                    arena.setCenterSpawn(SpawnLocationUtil.findSafeSpawn(
                            world, (int) cx, (int) cz, baseY));
                    arena.setTerrainSeed(seed);
                    arena.setGenerated(true);
                    if ("volcano".equalsIgnoreCase(gameMode.getInitialTerrain())) {
                        VolcanoCaldera.activateCalderaLava(world, cx, centerSurface, cz);
                    }
                    plugin.getArenaManager().save();
                    int filled = 0;
                    if (plugin.getLootBoxManager().usesCornucopiaChests(gameMode)) {
                        filled = plugin.getLootBoxManager().fillCornucopiaChests(arena, gameMode.getLootTier());
                    }
                    if (progressBar != null) {
                        progressBar.setTitle("Saving arena copy...");
                        progressBar.setProgress(1.0);
                    }
                    int filledChests = filled;
                    plugin.getArenaManager().captureTemplate(arena, success -> {
                        if (progressBar != null) {
                            progressBar.removeAll();
                        }
                        if (player != null) {
                            player.sendMessage(plugin.formatMessage("&aArena &e" + arena.getName() + " " + successVerb));
                            if (filledChests > 0) {
                                player.sendMessage(plugin.formatMessage("&7Cornucopia chests filled with &e" + filledChests
                                        + " &7container(s)."));
                            }
                            if (!success) {
                                player.sendMessage(plugin.formatMessage(
                                        "&cCould not save a reset copy. The next reset will rebuild the map."));
                            }
                        }
                        if (onComplete != null) {
                            onComplete.run();
                        }
                    });
                });
    }

    private void clearInteriorAboveSurface(List<BlockBatchUtil.BlockChange> changes, int[][] heightmap,
                                           int minX, int maxX, int minZ, int maxZ,
                                           double cx, double cz, int radius) {
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (!CircleUtil.isInsideCircle(cx, cz, x, z, radius)) {
                    continue;
                }
                int surfaceY = TerrainHeightmap.getHeight(heightmap, minX, minZ, x, z);
                for (int y = surfaceY + 1; y <= CLEAR_HEIGHT; y++) {
                    changes.add(new BlockBatchUtil.BlockChange(x, y, z, Material.AIR));
                }
            }
        }
    }

    private void buildInteriorColumn(List<BlockBatchUtil.BlockChange> changes, Arena arena, GameMode gameMode,
                                     double[] groveArea, double[] settlementSites, List<VolcanoPonds.Pond> ponds,
                                     int[][] heightmap, int minX, int minZ,
                                     int x, int z, int seed, double cx, double cz, int radius) {
        int surfaceY = TerrainHeightmap.getHeight(heightmap, minX, minZ, x, z);
        boolean volcanoMode = "volcano".equalsIgnoreCase(gameMode.getInitialTerrain());
        for (int y = 1; y < surfaceY; y++) {
            Material mat = getSubsurfaceMaterial(gameMode.getInitialTerrain(), x, y, z, surfaceY, seed, cx, cz);
            mat = ArcticRing.subsurfaceMaterial(arena, gameMode, x, y, z, surfaceY, seed, mat);
            changes.add(new BlockBatchUtil.BlockChange(x, y, z, mat));
        }
        Material surface = getSurfaceMaterial(gameMode.getInitialTerrain(), x, z, seed, cx, cz);
        surface = ArcticRing.surfaceMaterial(arena, gameMode, x, z, seed, surface);
        changes.add(new BlockBatchUtil.BlockChange(x, surfaceY, z, surface));

        if (!ArcticRing.isArcticColumn(arena, gameMode, x, z)
                && !VolcanoPonds.isInside(ponds, x, z, 1.5)
                && !shouldPlantTree(arena, gameMode, groveArea, settlementSites, ponds, heightmap, minX, minZ,
                x, z, surface, seed, cx, cz, radius)) {
            addSurfaceDecoration(changes, surface, x, surfaceY + 1, z, seed, volcanoMode);
        }
        if (ArcticRing.isArcticColumn(arena, gameMode, x, z) && surface == Material.SNOW_BLOCK
                && NoiseUtil.continuousNoise2D(x * 0.27, z * 0.27, seed + 55) > 0.15) {
            changes.add(new BlockBatchUtil.BlockChange(x, surfaceY + 1, z, Material.SNOW));
        }
    }

    private void plantInteriorTrees(List<BlockBatchUtil.BlockChange> changes, Arena arena, GameMode gameMode,
                                    double[] groveArea, double[] settlementSites, List<VolcanoPonds.Pond> ponds,
                                    int[][] heightmap, int minX, int maxX, int minZ, int maxZ,
                                    int seed, double cx, double cz, int radius) {
        TreePlacementConfig trees = gameMode.getTreePlacement();
        if (trees == null || !trees.isEnabled()) {
            return;
        }
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (!CircleUtil.isInsideCircle(cx, cz, x, z, radius)) {
                    continue;
                }
                int surfaceY = TerrainHeightmap.getHeight(heightmap, minX, minZ, x, z);
                Material surface = getSurfaceMaterial(gameMode.getInitialTerrain(), x, z, seed, cx, cz);
                if (shouldPlantTree(arena, gameMode, groveArea, settlementSites, ponds, heightmap, minX, minZ,
                        x, z, surface, seed, cx, cz, radius)) {
                    TreePlacement.addTree(changes, x, surfaceY + 1, z, seed, trees);
                }
            }
        }
    }

    private boolean shouldPlantTree(Arena arena, GameMode gameMode, double[] groveArea,
                                    double[] settlementSites, List<VolcanoPonds.Pond> ponds,
                                    int[][] heightmap, int minX, int minZ,
                                    int x, int z, Material surface, int seed,
                                    double cx, double cz, int radius) {
        if (JungleGrove.isInside(groveArea, x, z)
                || Settlements.isNearAnySite(settlementSites, x, z)
                || VolcanoPonds.isInside(ponds, x, z, 1.5)
                || ArcticRing.isArcticColumn(arena, gameMode, x, z)) {
            return false;
        }
        TreePlacementConfig trees = gameMode.getTreePlacement();
        return trees != null && trees.isEnabled()
                && TreePlacement.shouldPlaceTree(x, z, cx, cz, radius, seed, surface, trees)
                && !VolcanoCaldera.isInsideCalderaArea(cx, cz, x, z)
                && (!trees.isRequireFlat()
                || TerrainHeightmap.isFlatArea(heightmap, minX, minZ, x, z, 2));
    }

    private Material getSubsurfaceMaterial(String terrain, int x, int y, int z, int surfaceY, int seed,
                                           double cx, double cz) {
        if ("volcano".equalsIgnoreCase(terrain)) {
            if (CircleUtil.isInsideCircle(cx, cz, x, z, VolcanoCaldera.NO_TREE_RADIUS)) {
                return Material.NETHERRACK;
            }
            int depth = surfaceY - y;
            if (depth > 6) {
                double n = NoiseUtil.continuousNoise2D(x * 0.2, z * 0.2 + y * 0.05, seed + 17);
                return n > 0.15 ? Material.BASALT : Material.TUFF;
            }
            if (depth > 3) {
                return NoiseUtil.noise2D(x, z, seed + y) > 0.1 ? Material.BASALT : Material.COARSE_DIRT;
            }
            return Material.COARSE_DIRT;
        }
        return y < surfaceY - 4 ? Material.STONE : Material.DIRT;
    }

    private Material getSurfaceMaterial(String terrain, int x, int z, int seed, double cx, double cz) {
        if ("barren".equalsIgnoreCase(terrain)) {
            return Material.COARSE_DIRT;
        }
        if ("volcano".equalsIgnoreCase(terrain)) {
            if (CircleUtil.isInsideCircle(cx, cz, x, z, VolcanoCaldera.NO_TREE_RADIUS)) {
                return Material.NETHERRACK;
            }
            double macro = NoiseUtil.continuousFractalNoise2D(x * 0.035, z * 0.035, seed, 3);
            double micro = NoiseUtil.continuousFractalNoise2D(x * 0.14, z * 0.14, seed + 51, 2);
            if (macro > 0.35) {
                return Material.BASALT;
            }
            if (macro > 0.12) {
                return micro > 0.1 ? Material.SMOOTH_BASALT : Material.BASALT;
            }
            if (macro > -0.08) {
                return micro > 0.25 ? Material.GRAVEL : Material.COARSE_DIRT;
            }
            return micro > 0.2 ? Material.PODZOL : Material.GRASS_BLOCK;
        }
        if ("mixed".equalsIgnoreCase(terrain)) {
            return NoiseUtil.noise2D(x, z, seed) > 0 ? Material.GRASS_BLOCK : Material.PODZOL;
        }
        return Material.GRASS_BLOCK;
    }

    private void addSurfaceDecoration(List<BlockBatchUtil.BlockChange> changes, Material surface,
                                      int x, int y, int z, int seed, boolean volcanoMode) {
        if (volcanoMode) {
            if (surface == Material.GRASS_BLOCK || surface == Material.PODZOL) {
                double decor = NoiseUtil.continuousNoise2D(x * 0.25, z * 0.25, seed + 55);
                if (decor > 0.45) {
                    changes.add(new BlockBatchUtil.BlockChange(x, y, z, Material.TALL_GRASS));
                } else if (decor > 0.2) {
                    changes.add(new BlockBatchUtil.BlockChange(x, y, z, Material.SHORT_GRASS));
                }
            } else if (surface == Material.GRAVEL || surface == Material.COARSE_DIRT) {
                if (NoiseUtil.continuousNoise2D(x * 0.31, z * 0.31, seed + 88) > 0.55) {
                    changes.add(new BlockBatchUtil.BlockChange(x, y, z, Material.DEAD_BUSH));
                }
            }
            return;
        }
        if (surface == Material.GRASS_BLOCK && NoiseUtil.noise2D(x, z, seed + 55) > 0.7) {
            changes.add(new BlockBatchUtil.BlockChange(x, y, z, Material.TALL_GRASS));
        }
    }

    private void addCenterFeature(List<BlockBatchUtil.BlockChange> changes, GameMode gameMode,
                                  double cx, int surfaceY, double cz) {
        if ("volcano".equalsIgnoreCase(gameMode.getInitialTerrain())) {
            VolcanoCaldera.addCaldera(changes, cx, surfaceY, cz);
        } else {
            addCornucopiaPlatform(changes, cx, surfaceY, cz);
        }
    }

    private void addCornucopiaPlatform(List<BlockBatchUtil.BlockChange> changes, double cx, int surfaceY, double cz) {
        int icx = (int) cx;
        int icz = (int) cz;
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                if (dx * dx + dz * dz <= 12) {
                    changes.add(new BlockBatchUtil.BlockChange(icx + dx, surfaceY + 1, icz + dz, Material.GOLD_BLOCK));
                }
            }
        }
        changes.add(new BlockBatchUtil.BlockChange(icx, surfaceY + 1, icz, Material.CHEST));
        changes.add(new BlockBatchUtil.BlockChange(icx + 3, surfaceY + 1, icz, Material.CHEST));
        changes.add(new BlockBatchUtil.BlockChange(icx - 3, surfaceY + 1, icz, Material.CHEST));
        changes.add(new BlockBatchUtil.BlockChange(icx, surfaceY + 1, icz + 3, Material.CHEST));
    }

    private void applyBiomes(World world, Arena arena, GameMode gameMode) {
        String terrain = gameMode.getInitialTerrain().toLowerCase();
        Biome defaultBiome = switch (terrain) {
            case "barren" -> Biome.SAVANNA;
            case "mixed" -> Biome.PLAINS;
            case "volcano" -> Biome.FOREST;
            default -> Biome.FOREST;
        };
        int radius = arena.getRadius();
        double cx = arena.getCenterX();
        double cz = arena.getCenterZ();
        double[] groveArea = JungleGrove.groveArea(arena, gameMode);
        for (int x = (int) (cx - radius); x <= cx + radius; x++) {
            for (int z = (int) (cz - radius); z <= cz + radius; z++) {
                if (CircleUtil.isInsideCircle(cx, cz, x, z, radius)) {
                    Biome biome = defaultBiome;
                    if ("volcano".equals(terrain)) {
                        if (CircleUtil.isInsideCircle(cx, cz, x, z, VolcanoCaldera.NO_TREE_RADIUS)) {
                            biome = Biome.SOUL_SAND_VALLEY;
                        } else if (JungleGrove.isInside(groveArea, x, z)) {
                            biome = Biome.JUNGLE;
                        } else {
                            Biome arctic = ArcticRing.biomeAt(arena, gameMode, x, z);
                            if (arctic != null) {
                                biome = arctic;
                            }
                        }
                    }
                    world.setBiome(x, (int) arena.getCenterY(), z, biome);
                }
            }
        }
    }

    private void setupWorldBorder(World world, Arena arena) {
        world.getWorldBorder().setCenter(arena.getCenterX(), arena.getCenterZ());
        world.getWorldBorder().setSize(arena.getRadius() * 2.0);
        world.getWorldBorder().setDamageBuffer(0);
        world.getWorldBorder().setDamageAmount(1.0);
        world.getWorldBorder().setWarningDistance(5);
    }

    private List<Location> computeSpawnRing(World world, Arena arena, GameMode gameMode) {
        List<Location> spawns = new ArrayList<>();
        int forest = gameMode.getForestRadius();
        int count = Math.max(8, Math.min(24, forest / 5));
        double spawnRadius = forest * 0.8;
        double cx = arena.getCenterX();
        double cz = arena.getCenterZ();
        int baseY = (int) arena.getCenterY();

        for (int i = 0; i < count; i++) {
            double angle = (2 * Math.PI * i) / count;
            int x = (int) Math.round(cx + Math.cos(angle) * spawnRadius);
            int z = (int) Math.round(cz + Math.sin(angle) * spawnRadius);
            spawns.add(SpawnLocationUtil.findSafeSpawn(world, x, z, baseY));
        }
        return spawns;
    }

    public int findSurfaceAt(World world, int x, int z, int baseY) {
        return SpawnLocationUtil.findGroundY(world, x, z, baseY);
    }
}
