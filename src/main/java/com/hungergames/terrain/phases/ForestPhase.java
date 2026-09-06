package com.hungergames.terrain.phases;

import com.hungergames.HungerGamesPlugin;
import com.hungergames.arena.Arena;
import com.hungergames.game.Game;
import com.hungergames.gamemode.TerrainPhaseConfig;
import com.hungergames.terrain.PhaseDurationResolver;
import com.hungergames.terrain.TerrainPhase;
import com.hungergames.util.ArenaEntityUtil;

public class ForestPhase implements TerrainPhase {

    private final HungerGamesPlugin plugin;
    private long endTime;
    private boolean continuous;

    public ForestPhase(HungerGamesPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "FOREST";
    }

    @Override
    public void onStart(Game game, Arena arena, TerrainPhaseConfig config) {
        continuous = PhaseDurationResolver.isContinuous(config);
        int resolved = plugin.getTerrainPhaseManager().getResolvedDurationSeconds();
        endTime = continuous ? Long.MAX_VALUE : System.currentTimeMillis() + resolved * 1000L;
        ArenaEntityUtil.clearUntaggedHostiles(arena.getWorld(), arena);
    }

    @Override
    public void onTick(Game game, Arena arena, TerrainPhaseConfig config) {
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
    }
}
