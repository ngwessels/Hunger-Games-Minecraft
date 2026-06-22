package com.hungergames.terrain;

import com.hungergames.arena.Arena;
import com.hungergames.game.Game;
import com.hungergames.gamemode.TerrainPhaseConfig;

public interface TerrainPhase {

    String id();

    void onStart(Game game, Arena arena, TerrainPhaseConfig config);

    void onTick(Game game, Arena arena, TerrainPhaseConfig config);

    boolean isComplete();

    void onEnd(Game game, Arena arena, TerrainPhaseConfig config);
}
