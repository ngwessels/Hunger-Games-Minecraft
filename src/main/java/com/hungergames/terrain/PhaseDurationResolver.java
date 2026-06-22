package com.hungergames.terrain;

import com.hungergames.config.PluginConfig;
import com.hungergames.game.Game;
import com.hungergames.game.MatchPacing;
import com.hungergames.gamemode.TerrainPhaseConfig;

public final class PhaseDurationResolver {

    private PhaseDurationResolver() {
    }

    /**
     * @return resolved duration in seconds, 0 for continuous phases
     */
    public static int resolveSeconds(Game game, PluginConfig config, TerrainPhaseConfig phaseConfig) {
        if (phaseConfig.getDurationSeconds() <= 0) {
            return 0;
        }

        double matchRatio = phaseConfig.getDouble("durationMatchRatio", 0);
        if (matchRatio > 0) {
            return MatchPacing.deriveSeconds(game, config, 0, matchRatio, phaseConfig.getDurationSeconds());
        }

        return phaseConfig.getDurationSeconds();
    }

    public static boolean isContinuous(TerrainPhaseConfig phaseConfig) {
        return phaseConfig.getDurationSeconds() <= 0;
    }
}
