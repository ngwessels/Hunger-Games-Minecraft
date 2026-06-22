package com.hungergames.terrain.phases;

import com.hungergames.HungerGamesPlugin;
import com.hungergames.terrain.TerrainPhase;

public final class TerrainPhaseFactory {

    private TerrainPhaseFactory() {
    }

    public static TerrainPhase create(HungerGamesPlugin plugin, String type) {
        return switch (type.toUpperCase()) {
            case "FOREST" -> new ForestPhase(plugin);
            case "NETHER_TRANSFORM" -> new NetherTransformPhase(plugin);
            case "OCEAN_RISE" -> new OceanRisePhase(plugin);
            case "LAVA_RISE" -> new LavaRisePhase(plugin);
            case "BORDER_SHRINK" -> new BorderShrinkPhase(plugin);
            case "STORM" -> new StormPhase(plugin);
            case "VOLCANO_ERUPTION" -> new VolcanoEruptionPhase(plugin);
            default -> new ForestPhase(plugin);
        };
    }
}
