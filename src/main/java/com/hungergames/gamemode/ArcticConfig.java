package com.hungergames.gamemode;

import org.bukkit.configuration.ConfigurationSection;

/**
 * Outer frozen ring on volcano arenas: ice/glaciers beyond the forest radius.
 */
public class ArcticConfig {

    private boolean enabled;
    private int forestRadius = 300;
    private int blendWidth = 12;
    private int polarBearCount = 8;
    private int glacierCount = 6;
    private double polarBearAggroRange = 28.0;

    public static ArcticConfig fromYaml(ConfigurationSection section) {
        ArcticConfig config = new ArcticConfig();
        if (section == null) {
            return config;
        }
        config.enabled = section.getBoolean("enabled", false);
        config.forestRadius = Math.max(20, section.getInt("forestRadius", 300));
        config.blendWidth = Math.max(4, section.getInt("blendWidth", 12));
        config.polarBearCount = Math.max(0, section.getInt("polarBearCount", 8));
        config.glacierCount = Math.max(0, section.getInt("glacierCount", 6));
        config.polarBearAggroRange = Math.max(8.0, section.getDouble("polarBearAggroRange", 28.0));
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
}
