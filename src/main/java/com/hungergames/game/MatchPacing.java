package com.hungergames.game;

import com.hungergames.config.PluginConfig;

public final class MatchPacing {

    private MatchPacing() {
    }

    public static int getMaxMatchSeconds(Game game, PluginConfig config) {
        int modeLimit = game.getGameMode().getMaxMatchSeconds();
        if (modeLimit > 0) {
            return modeLimit;
        }
        return config.getDefaultMaxMatchSeconds();
    }

    public static boolean hasTimeLimit(Game game, PluginConfig config) {
        return getMaxMatchSeconds(game, config) > 0;
    }

    public static int getRemainingSeconds(Game game, PluginConfig config) {
        int max = getMaxMatchSeconds(game, config);
        if (max <= 0) {
            return -1;
        }
        return Math.max(0, max - game.getMatchElapsedSeconds());
    }

    public static double getMatchProgress(Game game, PluginConfig config) {
        int max = getMaxMatchSeconds(game, config);
        if (max <= 0) {
            return -1;
        }
        return Math.min(1.0, game.getMatchElapsedSeconds() / (double) max);
    }

    public static int deriveSeconds(Game game, PluginConfig config, int explicitSeconds,
                                  double matchRatio, int fallbackSeconds) {
        int max = getMaxMatchSeconds(game, config);
        if (max > 0) {
            return Math.max(1, (int) Math.round(max * matchRatio));
        }
        if (explicitSeconds > 0) {
            return explicitSeconds;
        }
        return fallbackSeconds;
    }
}
