package com.hungergames.gamemode;

import org.bukkit.configuration.ConfigurationSection;

/**
 * Sky comet that closes in over a volcano match and obliterates the arena at the end.
 */
public class CometConfig {

    private boolean enabled;
    private double startHeight = 260;
    private double startOffsetX = 340;
    private double startOffsetZ = 220;
    private double impactHeight = 32;
    private float startScale = 3.0f;
    private float endScale = 16.0f;
    private float viewRange = 28.0f;
    private int diveTicks = 50;
    private int obliterateColumnsPerTick = 1800;
    private boolean gravityEnabled = true;
    private int gravityMaxZones = 3;
    private double gravityMinRadius = 16;
    private double gravityMaxRadius = 42;
    private int gravityMinDurationSeconds = 8;
    private int gravityMaxDurationSeconds = 18;
    private double gravitySpawnChancePerSecond = 0.22;

    public static CometConfig fromYaml(ConfigurationSection section) {
        CometConfig config = new CometConfig();
        if (section == null) {
            return config;
        }
        config.enabled = section.getBoolean("enabled", false);
        config.startHeight = Math.max(80.0, section.getDouble("startHeight", 260));
        config.startOffsetX = section.getDouble("startOffsetX", 340);
        config.startOffsetZ = section.getDouble("startOffsetZ", 220);
        config.impactHeight = Math.max(8.0, section.getDouble("impactHeight", 32));
        config.startScale = (float) Math.max(1.0, section.getDouble("startScale", 3.0));
        config.endScale = (float) Math.max(config.startScale, section.getDouble("endScale", 16.0));
        config.viewRange = (float) Math.max(4.0, section.getDouble("viewRange", 28.0));
        config.diveTicks = Math.max(20, section.getInt("diveTicks", 50));
        config.obliterateColumnsPerTick = Math.max(200, section.getInt("obliterateColumnsPerTick", 1800));
        config.gravityEnabled = section.getBoolean("gravityEnabled", true);
        config.gravityMaxZones = Math.max(1, section.getInt("gravityMaxZones", 3));
        config.gravityMinRadius = Math.max(8.0, section.getDouble("gravityMinRadius", 16));
        config.gravityMaxRadius = Math.max(config.gravityMinRadius,
                section.getDouble("gravityMaxRadius", 42));
        config.gravityMinDurationSeconds = Math.max(4, section.getInt("gravityMinDurationSeconds", 8));
        config.gravityMaxDurationSeconds = Math.max(config.gravityMinDurationSeconds,
                section.getInt("gravityMaxDurationSeconds", 18));
        config.gravitySpawnChancePerSecond = Math.max(0.02,
                section.getDouble("gravitySpawnChancePerSecond", 0.22));
        return config;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public double getStartHeight() {
        return startHeight;
    }

    public double getStartOffsetX() {
        return startOffsetX;
    }

    public double getStartOffsetZ() {
        return startOffsetZ;
    }

    public double getImpactHeight() {
        return impactHeight;
    }

    public float getStartScale() {
        return startScale;
    }

    public float getEndScale() {
        return endScale;
    }

    public float getViewRange() {
        return viewRange;
    }

    public int getDiveTicks() {
        return diveTicks;
    }

    public int getObliterateColumnsPerTick() {
        return obliterateColumnsPerTick;
    }

    public boolean isGravityEnabled() {
        return gravityEnabled;
    }

    public int getGravityMaxZones() {
        return gravityMaxZones;
    }

    public double getGravityMinRadius() {
        return gravityMinRadius;
    }

    public double getGravityMaxRadius() {
        return gravityMaxRadius;
    }

    public int getGravityMinDurationSeconds() {
        return gravityMinDurationSeconds;
    }

    public int getGravityMaxDurationSeconds() {
        return gravityMaxDurationSeconds;
    }

    public double getGravitySpawnChancePerSecond() {
        return gravitySpawnChancePerSecond;
    }
}
