package com.hungergames.gamemode;

import org.bukkit.configuration.ConfigurationSection;

public class SettlementConfig {

    private boolean enabled;
    private int minRadius = 100;
    private int outpostsPer100Radius = 1;
    private int villagesPer100Radius = 1;
    private int ruinsBaseCount = 3;
    private int ruinsPer100Radius = 2;
    private int garrisonSize = 4;
    private int garrisonRespawnSeconds = 45;
    private int villagerCount = 5;
    private String chestLootTierFallback = "hard";
    private boolean condemnationEnabled = true;

    public static SettlementConfig fromYaml(ConfigurationSection section) {
        SettlementConfig config = new SettlementConfig();
        if (section == null) {
            return config;
        }
        config.enabled = section.getBoolean("enabled", false);
        config.minRadius = section.getInt("minRadius", 100);
        config.outpostsPer100Radius = Math.max(1, section.getInt("outpostsPer100Radius", 1));
        config.villagesPer100Radius = Math.max(1, section.getInt("villagesPer100Radius", 1));
        config.ruinsBaseCount = Math.max(0, section.getInt("ruinsBaseCount", 3));
        config.ruinsPer100Radius = Math.max(0, section.getInt("ruinsPer100Radius", 2));
        config.garrisonSize = Math.max(1, section.getInt("garrisonSize", 4));
        config.garrisonRespawnSeconds = Math.max(5, section.getInt("garrisonRespawnSeconds", 45));
        config.villagerCount = Math.max(1, section.getInt("villagerCount", 5));
        config.chestLootTierFallback = section.getString("chestLootTierFallback", "hard");
        config.condemnationEnabled = section.getBoolean("condemnationEnabled", true);
        return config;
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

    public int getOutpostsPer100Radius() {
        return outpostsPer100Radius;
    }

    public int getVillagesPer100Radius() {
        return villagesPer100Radius;
    }

    public int getRuinsBaseCount() {
        return ruinsBaseCount;
    }

    public int getRuinsPer100Radius() {
        return ruinsPer100Radius;
    }

    public int getGarrisonSize() {
        return garrisonSize;
    }

    public int getGarrisonRespawnSeconds() {
        return garrisonRespawnSeconds;
    }

    public int getVillagerCount() {
        return villagerCount;
    }

    public String getChestLootTierFallback() {
        return chestLootTierFallback;
    }

    public boolean isCondemnationEnabled() {
        return condemnationEnabled;
    }
}
