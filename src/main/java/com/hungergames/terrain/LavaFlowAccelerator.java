package com.hungergames.terrain;

import com.hungergames.HungerGamesPlugin;
import com.hungergames.arena.Arena;
import com.hungergames.game.Game;
import com.hungergames.game.GameState;
import com.hungergames.util.CircleUtil;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;

/**
 * Gives arena lava extra physics updates so caldera and trench lava spreads faster
 * than vanilla random ticks alone, without changing the global random-tick game rule.
 */
public final class LavaFlowAccelerator {

    private static final int BASE_NUDGES_PER_TICK = 40;
    private static final int MAX_NUDGES_PER_TICK = 80;
    private static final int MAX_COLUMN_SCANS_PER_TICK = 400;

    private final HungerGamesPlugin plugin;
    private final Map<String, Integer> scanCursor = new HashMap<>();
    private BukkitTask task;

    public LavaFlowAccelerator(HungerGamesPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        if (task != null) {
            return;
        }
        task = new BukkitRunnable() {
            @Override
            public void run() {
                double multiplier = plugin.getPluginConfig().getLavaFlowSpeedMultiplier();
                if (multiplier <= 1.0) {
                    return;
                }
                int extraBudget = Math.min(
                        MAX_NUDGES_PER_TICK,
                        Math.max(1, (int) Math.round(BASE_NUDGES_PER_TICK * (multiplier - 1.0))));
                for (Game game : plugin.getGameManager().getRunningGames()) {
                    if (game.getState() != GameState.ACTIVE) {
                        continue;
                    }
                    tickArena(game, extraBudget);
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    public void shutdown() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        scanCursor.clear();
    }

    private void tickArena(Game game, int budget) {
        Arena arena = game.getArena();
        World world = arena.getWorld();
        if (world == null) {
            return;
        }

        double cx = arena.getCenterX();
        double cz = arena.getCenterZ();
        int radius = arena.getRadius();
        int minX = (int) (cx - radius);
        int maxX = (int) (cx + radius);
        int minZ = (int) (cz - radius);
        int maxZ = (int) (cz + radius);
        int width = maxX - minX + 1;
        int depth = maxZ - minZ + 1;
        int area = Math.max(1, width * depth);

        String key = arena.getName().toLowerCase();
        int cursor = scanCursor.getOrDefault(key, 0);
        int minY = 1;
        int maxY = arena.getWallHeight();

        int nudged = 0;
        int columnsScanned = 0;
        while (nudged < budget && columnsScanned < MAX_COLUMN_SCANS_PER_TICK) {
            columnsScanned++;
            int index = cursor % area;
            cursor++;
            int x = minX + (index % width);
            int z = minZ + (index / width);
            if (!CircleUtil.isInsideCircle(cx, cz, x, z, radius)) {
                continue;
            }

            for (int y = maxY; y >= minY; y--) {
                Block block = world.getBlockAt(x, y, z);
                if (block.getType() != Material.LAVA) {
                    continue;
                }
                nudgeLavaBlock(block);
                nudged++;
                break;
            }
        }

        scanCursor.put(key, cursor);
    }

    private static void nudgeLavaBlock(Block block) {
        block.setBlockData(block.getBlockData(), true);
    }
}
