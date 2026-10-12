package com.hungergames.gamemode;

import org.bukkit.configuration.ConfigurationSection;

/**
 * Frozen / cold terrain on volcano arenas. When enabled, the disc inside
 * {@code forestRadius} generates as patchy tundra and the outer ring beyond
 * that radius stays an ice-ocean belt with glaciers.
 */
public class ArcticConfig {

    private boolean enabled;
    private int forestRadius = 300;
    private int blendWidth = 12;
    private int polarBearCount = 8;
    private int glacierCount = 6;
    private double polarBearAggroRange = 28.0;
    /** Noise frequency for snow/grass patches (higher = smaller patches). */
    private double tundraPatchScale = 0.045;
    /** Fraction of the tundra surface that is snowy (0–1). */
    private double tundraSnowRatio = 0.45;

    public static ArcticConfig fromYaml(ConfigurationSection section) {
        ArcticConfig config = new ArcticConfig();
        if (section == null) {
            return config;
        }
        config.enabled = section.getBoolean("enabled", false);
        // iceWorldStart is ignored: whole-disc ice sheets were replaced by patchy tundra.
        config.forestRadius = Math.max(20, section.getInt("forestRadius", 300));
        config.blendWidth = Math.max(4, section.getInt("blendWidth", 12));
        config.polarBearCount = Math.max(0, section.getInt("polarBearCount", 8));
        config.glacierCount = Math.max(0, section.getInt("glacierCount", 6));
        config.polarBearAggroRange = Math.max(8.0, section.getDouble("polarBearAggroRange", 28.0));
        config.tundraPatchScale = Math.max(0.005, Math.min(0.5, section.getDouble("tundraPatchScale", 0.045)));
        config.tundraSnowRatio = Math.max(0.0, Math.min(1.0, section.getDouble("tundraSnowRatio", 0.45)));
        return config;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getForestRadius() {
        return forestRadius;
    }

    public int getBlendWidth() {
        return blendWidth;
    }

    public int getPolarBearCount() {
        return polarBearCount;
    }

    public int getGlacierCount() {
        return glacierCount;
    }

    public double getPolarBearAggroRange() {
        return polarBearAggroRange;
    }

    public double getTundraPatchScale() {
        return tundraPatchScale;
    }

    public double getTundraSnowRatio() {
        return tundraSnowRatio;
    }
}
