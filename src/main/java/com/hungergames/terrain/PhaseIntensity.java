package com.hungergames.terrain;

import com.hungergames.config.PluginConfig;
import com.hungergames.game.Game;
import com.hungergames.game.MatchPacing;
import com.hungergames.gamemode.TerrainPhaseConfig;

public final class PhaseIntensity {

    private PhaseIntensity() {
    }

    public static double getMatchProgress(Game game, PluginConfig config) {
        return MatchPacing.getMatchProgress(game, config);
    }

    public static double getIntensity(Game game, PluginConfig config, TerrainPhaseConfig phaseConfig,
                                      long phaseStartMs) {
        double matchProgress = MatchPacing.getMatchProgress(game, config);
        if (matchProgress >= 0) {
            return Math.min(1.0, matchProgress);
        }

        int rampSeconds = MatchPacing.deriveSeconds(
                game,
                config,
                phaseConfig.getInt("intensityRampSeconds", 0),
                phaseConfig.getDouble("intensityRampMatchRatio", 0.85),
                900);
        long elapsedMs = System.currentTimeMillis() - phaseStartMs;
        return Math.min(1.0, elapsedMs / (rampSeconds * 1000.0));
    }

    public static double scaleRate(double baseRate, double intensity, double minScale, double maxScale) {
        return baseRate * (minScale + (maxScale - minScale) * Math.min(1.0, intensity));
    }
}
