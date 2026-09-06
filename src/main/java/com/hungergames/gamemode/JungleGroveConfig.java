package com.hungergames.gamemode;

import org.bukkit.configuration.ConfigurationSection;

public class JungleGroveConfig {

    private boolean enabled;
    private int minRadius = 100;
    private int groveRadius = 20;
    private double centerOffsetFactor = 0.52;
    private int megaTreeCount = 7;
    private int minTrunkHeight = 14;
    private int maxTrunkHeight = 26;
    private int treehouseAnchors = 4;

    public static JungleGroveConfig fromYaml(ConfigurationSection section) {
        JungleGroveConfig config = new JungleGroveConfig();
        if (section == null) {
            return config;
        }
        config.enabled = section.getBoolean("enabled", false);
        config.minRadius = section.getInt("minRadius", 100);
        config.groveRadius = Math.max(8, section.getInt("groveRadius", 20));
        config.centerOffsetFactor = clamp(section.getDouble("centerOffsetFactor", 0.52), 0.30, 0.62);
        config.megaTreeCount = Math.max(3, section.getInt("megaTreeCount", 7));
        config.minTrunkHeight = Math.max(6, section.getInt("minTrunkHeight", 14));
        config.maxTrunkHeight = Math.max(config.minTrunkHeight + 1, section.getInt("maxTrunkHeight", 26));
        config.treehouseAnchors = Math.max(2, section.getInt("treehouseAnchors", 4));
        return config;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMinRadius() {
        return minRadius;
    }

    public int getGroveRadius() {
        return groveRadius;
    }

    public double getCenterOffsetFactor() {
        return centerOffsetFactor;
    }

    public int getMegaTreeCount() {
        return megaTreeCount;
    }

    public int getMinTrunkHeight() {
        return minTrunkHeight;
    }

    public int getMaxTrunkHeight() {
        return maxTrunkHeight;
    }

    public int getTreehouseAnchors() {
        return treehouseAnchors;
    }
}
