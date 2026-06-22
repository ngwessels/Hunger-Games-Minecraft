package com.hungergames.terrain.phases;

import com.hungergames.HungerGamesPlugin;
import com.hungergames.arena.Arena;
import com.hungergames.game.Game;
import com.hungergames.gamemode.TerrainPhaseConfig;
import com.hungergames.terrain.PhaseDurationResolver;
import com.hungergames.terrain.PhaseIntensity;
import com.hungergames.terrain.StormLightningUtil;
import com.hungergames.terrain.TerrainPhase;
import com.hungergames.terrain.VolcanoHeatUtil;
import org.bukkit.World;

import java.util.Random;

public class StormPhase implements TerrainPhase {

    private final HungerGamesPlugin plugin;
    private final Random random = new Random();
    private final long[] lastLightningMs = new long[1];
    private final int[] fireBurnCursor = new int[1];
    private final int[] fireDecayCursor = new int[1];

    private long endTime;
    private long phaseStartMs;
    private boolean continuous;

    public StormPhase(HungerGamesPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "STORM";
    }

    @Override
    public void onStart(Game game, Arena arena, TerrainPhaseConfig config) {
        phaseStartMs = plugin.getTerrainPhaseManager().getPhaseStartMillis();
        continuous = PhaseDurationResolver.isContinuous(config);
        int resolved = plugin.getTerrainPhaseManager().getResolvedDurationSeconds();
        endTime = continuous ? Long.MAX_VALUE : System.currentTimeMillis() + resolved * 1000L;
        lastLightningMs[0] = 0;
        fireBurnCursor[0] = 0;
        fireDecayCursor[0] = 0;

        World world = arena.getWorld();
        if (world != null) {
            world.setStorm(true);
            world.setThundering(true);
        }
    }

    @Override
    public void onTick(Game game, Arena arena, TerrainPhaseConfig config) {
        World world = arena.getWorld();
        if (world == null) {
            return;
        }
        if (!world.hasStorm()) {
            world.setStorm(true);
            world.setThundering(true);
        }

        double intensity = PhaseIntensity.getIntensity(game, plugin.getPluginConfig(), config, phaseStartMs);
        StormLightningUtil.tick(game, arena, config, intensity, lastLightningMs, random);

        int burnChecks = config.getInt("stormFireBurnChecksPerTick", 400);
        double burnChance = config.getDouble("stormFireBurnChancePerTick", 0.08);
        int wallHeight = arena.getWallHeight();
        int baseY = (int) arena.getCenterY();
        VolcanoHeatUtil.tickBurnSweep(world, arena.getCenterX(), arena.getCenterZ(),
                baseY, baseY + wallHeight, arena.getRadius(),
                burnChecks, fireBurnCursor, burnChance, random, null);

        double decayChance = config.getDouble("stormFireDecayChancePerTick", 0.20);
        int decayChecks = config.getInt("stormFireDecayChecksPerTick", 400);
        if (decayChance > 0) {
            VolcanoHeatUtil.tickFireDecaySweep(world, arena.getCenterX(), arena.getCenterZ(),
                    baseY, baseY + wallHeight, arena.getRadius(),
                    decayChecks, fireDecayCursor, decayChance, random, null);
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
            world.setStorm(false);
            world.setThundering(false);
        }
    }
}
