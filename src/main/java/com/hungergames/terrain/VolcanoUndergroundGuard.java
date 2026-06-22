package com.hungergames.terrain;

import com.hungergames.HungerGamesPlugin;
import com.hungergames.arena.Arena;
import com.hungergames.arena.VolcanoCaldera;
import com.hungergames.game.Game;
import com.hungergames.game.GameState;
import com.hungergames.game.MatchPacing;
import com.hungergames.gamemode.TerrainPhaseConfig;
import com.hungergames.util.CircleUtil;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;

/**
 * Fills underground tunnels with lava when players dig beneath the volcano footprint.
 * Digging elsewhere underground in the arena is unaffected.
 */
public final class VolcanoUndergroundGuard {

    private static final int DEFAULT_FLOOD_BUDGET = 48;
    private static final int CONE_ARENA_EDGE_MARGIN = 3;

    private VolcanoUndergroundGuard() {
    }

    public static void handleBlockBreak(HungerGamesPlugin plugin, Game game, Block block, Player player) {
        if (!"volcano".equals(game.getGameMode().getId())) {
            return;
        }
        if (game.getState() != GameState.ACTIVE && game.getState() != GameState.GRACE) {
            return;
        }

        Arena arena = game.getArena();
        World world = block.getWorld();
        if (world == null || !world.equals(arena.getWorld())) {
            return;
        }

        TerrainPhaseConfig eruptionConfig = findEruptionConfig(game).orElse(null);
        if (eruptionConfig != null && eruptionConfig.getInt("undergroundDigLavaEnabled", 1) == 0) {
            return;
        }

        int x = block.getX();
        int y = block.getY();
        int z = block.getZ();
        double footprint = computeFootprintRadius(plugin, game, arena, eruptionConfig);
        if (!isUnderVolcanoFootprint(arena, world, x, y, z, footprint)) {
            return;
        }

        int budget = eruptionConfig != null
                ? eruptionConfig.getInt("undergroundDigLavaFloodBudget", DEFAULT_FLOOD_BUDGET)
                : DEFAULT_FLOOD_BUDGET;

        Location origin = block.getLocation();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!world.isChunkLoaded(origin.getBlockX() >> 4, origin.getBlockZ() >> 4)) {
                return;
            }
            floodTunnelWithLava(world, arena, origin, footprint, budget);
            if (player.isOnline()) {
                player.playSound(player.getLocation(), Sound.BLOCK_LAVA_POP, 0.7f, 0.6f);
            }
        });
    }

    private static boolean isUnderVolcanoFootprint(Arena arena, World world, int x, int y, int z,
                                                   double footprintRadius) {
        double dist = CircleUtil.distanceFromCenter(arena.getCenterX(), arena.getCenterZ(), x, z);
        if (dist > footprintRadius) {
            return false;
        }
        int terrainY = VolcanoHeatUtil.findTerrainGroundY(world, x, z, (int) arena.getCenterY());
        return y < terrainY;
    }

    private static void floodTunnelWithLava(World world, Arena arena, Location origin,
                                             double footprintRadius, int budget) {
        Queue<long[]> queue = new ArrayDeque<>();
        Set<Long> visited = new HashSet<>();
        long startKey = blockKey(origin.getBlockX(), origin.getBlockY(), origin.getBlockZ());
        queue.add(new long[]{origin.getBlockX(), origin.getBlockY(), origin.getBlockZ()});
        visited.add(startKey);

        int filled = 0;
        while (!queue.isEmpty() && filled < budget) {
            long[] pos = queue.poll();
            int x = (int) pos[0];
            int y = (int) pos[1];
            int z = (int) pos[2];

            Block block = world.getBlockAt(x, y, z);
            if (!block.getType().isAir()) {
                continue;
            }
            if (!isInsideFootprint(arena, x, z, footprintRadius)) {
                continue;
            }

            VolcanoCaldera.placeLavaSource(block);
            filled++;

            enqueueIfAir(world, arena, visited, queue, x + 1, y, z, footprintRadius);
            enqueueIfAir(world, arena, visited, queue, x - 1, y, z, footprintRadius);
            enqueueIfAir(world, arena, visited, queue, x, y + 1, z, footprintRadius);
            enqueueIfAir(world, arena, visited, queue, x, y - 1, z, footprintRadius);
            enqueueIfAir(world, arena, visited, queue, x, y, z + 1, footprintRadius);
            enqueueIfAir(world, arena, visited, queue, x, y, z - 1, footprintRadius);
        }
    }

    private static void enqueueIfAir(World world, Arena arena, Set<Long> visited, Queue<long[]> queue,
                                     int x, int y, int z, double footprintRadius) {
        if (y < 1 || y > world.getMaxHeight() - 1) {
            return;
        }
        if (!isInsideFootprint(arena, x, z, footprintRadius)) {
            return;
        }
        long key = blockKey(x, y, z);
        if (!visited.add(key)) {
            return;
        }
        if (!world.getBlockAt(x, y, z).getType().isAir()) {
            return;
        }
        queue.add(new long[]{x, y, z});
    }

    private static boolean isInsideFootprint(Arena arena, int x, int z, double footprintRadius) {
        return CircleUtil.distanceFromCenter(arena.getCenterX(), arena.getCenterZ(), x, z) <= footprintRadius;
    }

    private static long blockKey(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38 | ((long) y & 0xFFFL) << 26 | ((long) z & 0x3FFFFFFL);
    }

    private static double computeFootprintRadius(HungerGamesPlugin plugin, Game game, Arena arena,
                                                 TerrainPhaseConfig eruptionConfig) {
        if (eruptionConfig == null) {
            return VolcanoCaldera.RIM_RADIUS;
        }

        boolean eruptionActive = "VOLCANO_ERUPTION".equals(plugin.getTerrainPhaseManager().getCurrentPhaseName());
        if (!eruptionActive) {
            return VolcanoCaldera.RIM_RADIUS;
        }

        long eruptionElapsedSec = plugin.getTerrainPhaseManager().getPhaseElapsedSeconds();
        double growthProgress = getGrowthProgress(plugin, game, eruptionConfig, eruptionElapsedSec);
        int minBase = eruptionConfig.getInt("baseRadius", 8);
        int maxBase = getMaxConeBaseRadius(arena, eruptionConfig);
        double baseGrowthScale = eruptionConfig.getDouble("coneBaseGrowthScale", 0.6);
        int targetBase = minBase + (int) Math.round((maxBase - minBase) * growthProgress * baseGrowthScale);
        return Math.max(VolcanoCaldera.RIM_RADIUS, targetBase);
    }

    private static double getGrowthProgress(HungerGamesPlugin plugin, Game game,
                                            TerrainPhaseConfig config, long eruptionElapsedSec) {
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

    private static int getMaxConeBaseRadius(Arena arena, TerrainPhaseConfig config) {
        int arenaEdge = Math.max(1, arena.getRadius() - CONE_ARENA_EDGE_MARGIN);
        int configuredCap = config.getInt("maxConeBaseRadius", 0);
        if (configuredCap > 0) {
            return Math.min(arenaEdge, configuredCap);
        }
        return arenaEdge;
    }

    private static Optional<TerrainPhaseConfig> findEruptionConfig(Game game) {
        return game.getGameMode().getTerrainPhases().stream()
                .filter(phase -> "VOLCANO_ERUPTION".equals(phase.getType()))
                .findFirst();
    }
}
