package com.hungergames.util;

import com.hungergames.arena.Arena;
import org.bukkit.Location;
import org.bukkit.World;

public final class ArenaBounds {

    /** Keeps players just inside the arena floor and world border edge. */
    private static final double EDGE_INSET = 0.35;

    private ArenaBounds() {
    }

    public static double getPlayRadius(Arena arena) {
        double arenaRadius = arena.getRadius();
        World world = arena.getWorld();
        if (world == null) {
            return arenaRadius - EDGE_INSET;
        }
        double borderRadius = world.getWorldBorder().getSize() / 2.0;
        return Math.min(arenaRadius, borderRadius) - EDGE_INSET;
    }

    public static boolean isInsidePlayArea(Arena arena, double x, double z) {
        return CircleUtil.isInsideCircle(
                arena.getCenterX(), arena.getCenterZ(), x, z, getPlayRadius(arena));
    }

    public static Location clampToPlayArea(Arena arena, Location location) {
        return CircleUtil.clampToCircle(
                arena.getCenterX(), arena.getCenterZ(), location, getPlayRadius(arena));
    }
}
