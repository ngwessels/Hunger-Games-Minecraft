package com.hungergames.gamemode;

import org.bukkit.configuration.ConfigurationSection;

/**
 * Tunables for the village (iron golem) and creeper rage systems.
 * Ambient creeper spawns wait until the opening forest phase ends;
 * triggered rages still run whenever a player starts them.
 */
public class RageConfig {

    private boolean golemRageEnabled = true;
    private int golemRageCount = 4;
    private double golemRageHealthBonus = 20.0;
    private double golemRageDamageBonus = 5.0;
    private double golemRageSpeedBonus = 0.30;
    private int golemRageVillageRadius = 32;

    private boolean creeperRageEnabled = true;
    private int creeperAmbientMax = 6;
    private double creeperAmbientSpawnChancePerTick = 0.015;
    private int creeperAmbientRadiusMin = 12;
    private int creeperAmbientRadiusMax = 45;
    private int creeperRageDurationSeconds = 10;
    private int creeperRageSpawnIntervalTicks = 20;
    private int creeperRagePerWave = 2;
    private int creeperFuseTicks = 25;

    public static RageConfig fromYaml(ConfigurationSection section) {
        RageConfig config = new RageConfig();
        if (section == null) {
            return config;
        }
        config.golemRageEnabled = section.getBoolean("golemRageEnabled", true);
        config.golemRageCount = Math.max(1, section.getInt("golemRageCount", 4));
        config.golemRageHealthBonus = section.getDouble("golemRageHealthBonus", 20.0);
        config.golemRageDamageBonus = section.getDouble("golemRageDamageBonus", 5.0);
        config.golemRageSpeedBonus = Math.max(0.0, section.getDouble("golemRageSpeedBonus", 0.30));
        config.golemRageVillageRadius = Math.max(8, section.getInt("golemRageVillageRadius", 32));

        config.creeperRageEnabled = section.getBoolean("creeperRageEnabled", true);
        config.creeperAmbientMax = Math.max(0, section.getInt("creeperAmbientMax", 6));
        config.creeperAmbientSpawnChancePerTick =
                section.getDouble("creeperAmbientSpawnChancePerTick", 0.015);
        config.creeperAmbientRadiusMin = Math.max(4, section.getInt("creeperAmbientRadiusMin", 12));
        config.creeperAmbientRadiusMax = Math.max(config.creeperAmbientRadiusMin + 4,
                section.getInt("creeperAmbientRadiusMax", 45));
        config.creeperRageDurationSeconds = Math.max(1, section.getInt("creeperRageDurationSeconds", 10));
        config.creeperRageSpawnIntervalTicks = Math.max(10, section.getInt("creeperRageSpawnIntervalTicks", 20));
        config.creeperRagePerWave = Math.max(1, section.getInt("creeperRagePerWave", 2));
        config.creeperFuseTicks = Math.max(10, section.getInt("creeperFuseTicks", 25));
        return config;
    }

    public boolean isGolemRageEnabled() {
        return golemRageEnabled;
    }

    public int getGolemRageCount() {
        return golemRageCount;
    }

    public double getGolemRageHealthBonus() {
        return golemRageHealthBonus;
    }

    public double getGolemRageDamageBonus() {
        return golemRageDamageBonus;
    }

    public double getGolemRageSpeedBonus() {
        return golemRageSpeedBonus;
    }

    public int getGolemRageVillageRadius() {
        return golemRageVillageRadius;
    }

    public boolean isCreeperRageEnabled() {
        return creeperRageEnabled;
    }

    public int getCreeperAmbientMax() {
        return creeperAmbientMax;
    }

    public double getCreeperAmbientSpawnChancePerTick() {
        return creeperAmbientSpawnChancePerTick;
    }

    public int getCreeperAmbientRadiusMin() {
        return creeperAmbientRadiusMin;
    }

    public int getCreeperAmbientRadiusMax() {
        return creeperAmbientRadiusMax;
    }

    public int getCreeperRageDurationSeconds() {
        return creeperRageDurationSeconds;
    }

    public int getCreeperRageSpawnIntervalTicks() {
        return creeperRageSpawnIntervalTicks;
    }

    public int getCreeperRagePerWave() {
        return creeperRagePerWave;
    }

    public int getCreeperFuseTicks() {
        return creeperFuseTicks;
    }
}
