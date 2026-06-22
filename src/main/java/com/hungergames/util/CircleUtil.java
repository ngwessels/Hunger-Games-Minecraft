package com.hungergames.util;

import org.bukkit.Location;

public final class CircleUtil {

    private CircleUtil() {
    }

    public static boolean isInsideCircle(double centerX, double centerZ, double x, double z, double radius) {
        double dx = x - centerX;
        double dz = z - centerZ;
        return dx * dx + dz * dz <= radius * radius;
    }

    public static boolean isInsideAnnulus(double centerX, double centerZ, double x, double z,
                                          double innerRadius, double outerRadius) {
        double dx = x - centerX;
        double dz = z - centerZ;
        double distSq = dx * dx + dz * dz;
        return distSq >= innerRadius * innerRadius && distSq <= outerRadius * outerRadius;
    }

    public static double distanceFromCenter(double centerX, double centerZ, double x, double z) {
        double dx = x - centerX;
        double dz = z - centerZ;
        return Math.sqrt(dx * dx + dz * dz);
    }

    public static Location clampToCircle(double centerX, double centerZ, Location location, double maxRadius) {
        double dx = location.getX() - centerX;
        double dz = location.getZ() - centerZ;
        double distSq = dx * dx + dz * dz;
        double maxSq = maxRadius * maxRadius;
        if (distSq <= maxSq) {
            return location;
        }
        double dist = Math.sqrt(distSq);
        Location clamped = location.clone();
        double scale = maxRadius / dist;
        clamped.setX(centerX + dx * scale);
        clamped.setZ(centerZ + dz * scale);
        return clamped;
    }
}
