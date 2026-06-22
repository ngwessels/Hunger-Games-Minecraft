package com.hungergames.util;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;

/**
 * Spawns particles with the correct data type for each {@link Particle} on 1.21+.
 * Passing the wrong data (e.g. {@link Color} on {@link Particle#ELECTRIC_SPARK},
 * or omitting {@link Float} on {@link Particle#LAVA}) throws at runtime.
 */
public final class ParticleUtil {

    private ParticleUtil() {
    }

    public static void spawn(World world, Particle particle, Location location,
                             int count, double offsetX, double offsetY, double offsetZ, double extra) {
        spawn(world, particle, location.getX(), location.getY(), location.getZ(),
                count, offsetX, offsetY, offsetZ, extra);
    }

    public static void spawn(World world, Particle particle, double x, double y, double z,
                             int count, double offsetX, double offsetY, double offsetZ, double extra) {
        if (particle.getDataType() == Void.class) {
            world.spawnParticle(particle, x, y, z, count, offsetX, offsetY, offsetZ, extra);
            return;
        }
        world.spawnParticle(particle, x, y, z, count, offsetX, offsetY, offsetZ, extra, resolveData(particle));
    }

    public static void spawnForced(World world, Particle particle, double x, double y, double z,
                                   int count, double offsetX, double offsetY, double offsetZ, double extra) {
        if (particle.getDataType() == Void.class) {
            world.spawnParticle(particle, x, y, z, count, offsetX, offsetY, offsetZ, extra, null, true);
            return;
        }
        world.spawnParticle(particle, x, y, z, count, offsetX, offsetY, offsetZ, extra,
                resolveData(particle), true);
    }

    public static Object resolveData(Particle particle) {
        Class<?> dataType = particle.getDataType();
        if (dataType == Void.class) {
            return null;
        }
        if (dataType == Color.class) {
            return Color.fromRGB(180, 220, 255);
        }
        if (dataType == Float.class) {
            return 1.0f;
        }
        return null;
    }
}
