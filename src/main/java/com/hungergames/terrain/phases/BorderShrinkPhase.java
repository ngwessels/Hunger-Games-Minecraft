package com.hungergames.terrain.phases;

import com.hungergames.HungerGamesPlugin;
import com.hungergames.arena.Arena;
import com.hungergames.game.Game;
import com.hungergames.gamemode.TerrainPhaseConfig;
import com.hungergames.terrain.TerrainPhase;
import org.bukkit.World;

public class BorderShrinkPhase implements TerrainPhase {

    private final HungerGamesPlugin plugin;
    private long endTime;
    private double shrinkPerSecond;
    private long lastShrink;

    public BorderShrinkPhase(HungerGamesPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "BORDER_SHRINK";
    }

    @Override
    public void onStart(Game game, Arena arena, TerrainPhaseConfig config) {
        int resolved = plugin.getTerrainPhaseManager().getResolvedDurationSeconds();
        endTime = System.currentTimeMillis() + resolved * 1000L;
        shrinkPerSecond = config.getDouble("shrinkPerTick", 0.3) * 20;
        lastShrink = System.currentTimeMillis();
    }

    @Override
    public void onTick(Game game, Arena arena, TerrainPhaseConfig config) {
        World world = arena.getWorld();
        if (world == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastShrink < 1000L) {
            return;
        }
        lastShrink = now;
        double newSize = Math.max(20, world.getWorldBorder().getSize() - shrinkPerSecond);
        world.getWorldBorder().setSize(newSize, 1);
    }

    @Override
    public boolean isComplete() {
        return System.currentTimeMillis() >= endTime;
    }

    @Override
    public void onEnd(Game game, Arena arena, TerrainPhaseConfig config) {
    }
}
