package com.hungergames.terrain.phases;

import com.hungergames.HungerGamesPlugin;
import com.hungergames.arena.Arena;
import com.hungergames.game.Game;
import com.hungergames.gamemode.TerrainPhaseConfig;
import com.hungergames.terrain.PhaseDurationResolver;
import com.hungergames.terrain.PhaseIntensity;
import com.hungergames.terrain.PhaseMobSpawner;
import com.hungergames.terrain.TerrainPhase;
import com.hungergames.util.ArenaEntityUtil;
import com.hungergames.util.CircleUtil;
import org.bukkit.Material;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

public class LavaRisePhase implements TerrainPhase {

    private final HungerGamesPlugin plugin;
    private final Random random = new Random();

    private long endTime;
    private long phaseStartMs;
    private boolean continuous;
    private int currentLevel;
    private int baseRisePerTick;
    private long lastRiseMs;
    private final List<int[]> pendingCells = new ArrayList<>();
    private boolean sliceActive;

    public LavaRisePhase(HungerGamesPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "LAVA_RISE";
    }

    @Override
    public void onStart(Game game, Arena arena, TerrainPhaseConfig config) {
        phaseStartMs = plugin.getTerrainPhaseManager().getPhaseStartMillis();
        continuous = PhaseDurationResolver.isContinuous(config);
        int resolved = plugin.getTerrainPhaseManager().getResolvedDurationSeconds();
        endTime = continuous ? Long.MAX_VALUE : System.currentTimeMillis() + resolved * 1000L;
        currentLevel = config.getInt("startLevel", 10);
        baseRisePerTick = config.getInt("riseBlocksPerTick", 1);
        lastRiseMs = System.currentTimeMillis();
        pendingCells.clear();
        sliceActive = false;
    }

    @Override
    public void onTick(Game game, Arena arena, TerrainPhaseConfig config) {
        World world = arena.getWorld();
        if (world == null) {
            return;
        }

        double intensity = PhaseIntensity.getIntensity(game, plugin.getPluginConfig(), config, phaseStartMs);
        PhaseMobSpawner.trySpawn(plugin, world, arena, config, intensity, random);

        int blocksPerTick = config.getInt("blocksPerTick", plugin.getPluginConfig().getBlocksPerTick());
        drainPending(blocksPerTick);

        if (sliceActive && pendingCells.isEmpty()) {
            sliceActive = false;
            currentLevel += Math.max(1, baseRisePerTick);
        }

        if (sliceActive || !pendingCells.isEmpty()) {
            return;
        }

        long riseIntervalMs = (long) (1000.0 / Math.max(0.5, PhaseIntensity.scaleRate(1.0, intensity, 0.6, 1.4)));
        if (System.currentTimeMillis() - lastRiseMs < riseIntervalMs) {
            return;
        }
        lastRiseMs = System.currentTimeMillis();
        beginSlice(arena);
    }

    private void beginSlice(Arena arena) {
        double cx = arena.getCenterX();
        double cz = arena.getCenterZ();
        int radius = arena.getRadius() - 1;
        pendingCells.clear();

        for (int x = (int) (cx - radius); x <= cx + radius; x++) {
            for (int z = (int) (cz - radius); z <= cz + radius; z++) {
                if (CircleUtil.isInsideCircle(cx, cz, x, z, radius)) {
                    pendingCells.add(new int[]{x, currentLevel, z});
                }
            }
        }
        sliceActive = !pendingCells.isEmpty();
    }

    private void drainPending(int budget) {
        Iterator<int[]> it = pendingCells.iterator();
        int placed = 0;
        while (it.hasNext() && placed < budget) {
            int[] cell = it.next();
            plugin.getBlockMutationQueue().enqueue(cell[0], cell[1], cell[2], Material.LAVA);
            it.remove();
            placed++;
        }
    }

    @Override
    public boolean isComplete() {
        if (continuous) {
            return false;
        }
        return System.currentTimeMillis() >= endTime;
    }

    @Override
    public void onEnd(Game game, Arena arena, TerrainPhaseConfig config) {
        World world = arena.getWorld();
        if (world != null) {
            ArenaEntityUtil.clearPhaseEntities(world, arena);
        }
        pendingCells.clear();
        sliceActive = false;
    }
}
