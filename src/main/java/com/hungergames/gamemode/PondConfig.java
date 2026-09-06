package com.hungergames.gamemode;

import org.bukkit.configuration.ConfigurationSection;

/**
 * Forest ponds and small lakes placed during volcano arena generation.
 */
public class PondConfig {

    private boolean enabled;
    private double pondsPer100Radius = 4.0;
    private double lakesPer100Radius = 0.8;
    private int pondMinRadius = 3;
    private int pondMaxRadius = 6;
    private int lakeMinRadius = 8;
    private int lakeMaxRadius = 13;
    private int pondDepth = 2;
    private int lakeDepth = 3;
    private int minSeparation = 14;
    private int calderaMargin = 10;
    private int spawnRingMargin = 8;
    private int wallMargin = 8;
    private int maxSlope = 3;

    public static PondConfig fromYaml(ConfigurationSection section) {
        PondConfig config = new PondConfig();
        if (section == null) {
            return config;
        }
        config.enabled = section.getBoolean("enabled", false);
        config.pondsPer100Radius = Math.max(0, section.getDouble("pondsPer100Radius", 4.0));
        config.lakesPer100Radius = Math.max(0, section.getDouble("lakesPer100Radius", 0.8));
        config.pondMinRadius = Math.max(2, section.getInt("pondMinRadius", 3));
        config.pondMaxRadius = Math.max(config.pondMinRadius, section.getInt("pondMaxRadius", 6));
        config.lakeMinRadius = Math.max(config.pondMaxRadius + 1, section.getInt("lakeMinRadius", 8));
        config.lakeMaxRadius = Math.max(config.lakeMinRadius, section.getInt("lakeMaxRadius", 13));
        config.pondDepth = Math.max(1, section.getInt("pondDepth", 2));
        config.lakeDepth = Math.max(config.pondDepth, section.getInt("lakeDepth", 3));
        config.minSeparation = Math.max(4, section.getInt("minSeparation", 14));
        config.calderaMargin = Math.max(0, section.getInt("calderaMargin", 10));
        config.spawnRingMargin = Math.max(0, section.getInt("spawnRingMargin", 8));
        config.wallMargin = Math.max(2, section.getInt("wallMargin", 8));
        config.maxSlope = Math.max(1, section.getInt("maxSlope", 3));
        return config;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public double getPondsPer100Radius() {
        return pondsPer100Radius;
    }

    public double getLakesPer100Radius() {
        return lakesPer100Radius;
    }

    public int getPondMinRadius() {
        return pondMinRadius;
    }

    public int getPondMaxRadius() {
        return pondMaxRadius;
    }

    public int getLakeMinRadius() {
        return lakeMinRadius;
    }

    public int getLakeMaxRadius() {
        return lakeMaxRadius;
    }

    public int getPondDepth() {
        return pondDepth;
    }

    public int getLakeDepth() {
        return lakeDepth;
    }

    public int getMinSeparation() {
        return minSeparation;
    }

    public int getCalderaMargin() {
        return calderaMargin;
    }

    public int getSpawnRingMargin() {
        return spawnRingMargin;
    }

    public int getWallMargin() {
        return wallMargin;
    }

    public int getMaxSlope() {
        return maxSlope;
    }
}
