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

public class OceanRisePhase implements TerrainPhase {

    private final HungerGamesPlugin plugin;
    private final Random random = new Random();

    private long endTime;
    private long phaseStartMs;
    private boolean continuous;
    private int currentLevel;
    private int startLevel;
    private int baseRisePerTick;
    private long lastLevelRiseMs;
    private double lastQueuedProgress = -1;
    private int lastQueuedLevel = -1;
    private final List<int[]> pendingCells = new ArrayList<>();

    public OceanRisePhase(HungerGamesPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "OCEAN_RISE";
    }

    @Override
    public void onStart(Game game, Arena arena, TerrainPhaseConfig config) {
        phaseStartMs = plugin.getTerrainPhaseManager().getPhaseStartMillis();
        continuous = PhaseDurationResolver.isContinuous(config);
        int resolved = plugin.getTerrainPhaseManager().getResolvedDurationSeconds();
        endTime = continuous ? Long.MAX_VALUE : System.currentTimeMillis() + resolved * 1000L;
        startLevel = config.getInt("startLevel", 18);
        currentLevel = startLevel;
        baseRisePerTick = config.getInt("riseBlocksPerTick", 1);
        lastLevelRiseMs = System.currentTimeMillis();
        lastQueuedProgress = -1;
        lastQueuedLevel = -1;
        pendingCells.clear();
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

        if (!pendingCells.isEmpty()) {
            return;
        }

        long riseIntervalMs = 2000;
        if (System.currentTimeMillis() - lastLevelRiseMs >= riseIntervalMs) {
            lastLevelRiseMs = System.currentTimeMillis();
            int scaledRise = Math.max(1, (int) Math.round(
                    PhaseIntensity.scaleRate(baseRisePerTick, intensity, 0.7, 1.3)));
            currentLevel += scaledRise;
        }

        double floodProgress = intensity;
        boolean progressAdvanced = lastQueuedProgress < 0
                || floodProgress - lastQueuedProgress >= 0.03;
        boolean levelChanged = lastQueuedLevel != currentLevel;
        if (progressAdvanced || levelChanged) {
            queueAnnulusSlice(arena, floodProgress, currentLevel);
            lastQueuedProgress = floodProgress;
            lastQueuedLevel = currentLevel;
        }
    }

    private void queueAnnulusSlice(Arena arena, double floodProgress, int y) {
        pendingCells.clear();
        double cx = arena.getCenterX();
        double cz = arena.getCenterZ();
        int outerRadius = arena.getRadius() - 1;
        int inner = (int) Math.floor(outerRadius * floodProgress);

        for (int x = (int) (cx - outerRadius); x <= cx + outerRadius; x++) {
            for (int z = (int) (cz - outerRadius); z <= cz + outerRadius; z++) {
                if (CircleUtil.isInsideAnnulus(cx, cz, x, z, inner, outerRadius)) {
                    pendingCells.add(new int[]{x, y, z});
                }
            }
        }
    }

    private void drainPending(int budget) {
        Iterator<int[]> it = pendingCells.iterator();
        int placed = 0;
        while (it.hasNext() && placed < budget) {
            int[] cell = it.next();
            plugin.getBlockMutationQueue().enqueue(cell[0], cell[1], cell[2], Material.WATER);
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
    }
}
