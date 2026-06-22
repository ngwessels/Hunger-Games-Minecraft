package com.hungergames.config;

import com.hungergames.HungerGamesPlugin;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;

public class PluginConfig {

    private final HungerGamesPlugin plugin;

    private int blocksPerTick;
    private int wallThickness;
    private int baseTerrainY;
    private int defaultWallHeight;
    private int defaultCountdown;
    private int defaultGracePeriod;
    private int respawnGraceSeconds;
    private int minPlayersDefault;
    private int endingDelaySeconds;
    private boolean endOnLastSurvivor;
    private int killsToWin;
    private int deathsToSpectate;
    private int defaultMaxMatchSeconds;
    private double lavaFlowSpeedMultiplier;
    private double matchHealMultiplier;
    private int borderShrinkDurationSeconds;
    private double borderShrinkPerTick;
    private boolean stripCustomOnMatchEnd;
    private int customItemGlobalWeight;
    private Material lootBoxBlockType;
    private int lootBoxMinDistanceFromCenter;
    private int lootBoxMinSpacing;
    private double lootBoxDensityPer100Radius;
    private double densityMultiplierLow;
    private double densityMultiplierMedium;
    private double densityMultiplierHigh;
    private String messagePrefix;

    private boolean powerUpsEnabled;
    private int powerUpMinIntervalSeconds;
    private int powerUpMaxIntervalSeconds;
    private int powerUpDespawnSeconds;
    private double powerUpPickupRadius;
    private int powerUpDropHeightBlocks;
    private int powerUpMinEffectDurationSeconds;
    private int powerUpPlayersPerSpawn;
    private int powerUpMinSpawnsPerWave;
    private int powerUpMaxSpawnsPerWave;
    private int powerUpPlayersPerActive;
    private int powerUpMaxActiveCap;
    private double powerUpUltimateSpawnChance;
    private int powerUpBeaconBeamHeightBlocks;
    private int powerUpBeaconBeamIntervalTicks;

    public PluginConfig(HungerGamesPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        FileConfiguration config = plugin.getConfig();

        blocksPerTick = config.getInt("generation.blocks-per-tick", 4000);
        wallThickness = config.getInt("generation.wall-thickness", 4);
        baseTerrainY = config.getInt("generation.base-terrain-y", 20);
        defaultWallHeight = config.getInt("generation.default-wall-height", 21);
        defaultCountdown = config.getInt("game.default-countdown", 10);
        defaultGracePeriod = config.getInt("game.default-grace-period", 30);
        respawnGraceSeconds = config.getInt("game.respawn-grace-seconds", 5);
        minPlayersDefault = config.getInt("game.min-players-default", 2);
        endingDelaySeconds = config.getInt("game.ending-delay-seconds", 10);
        endOnLastSurvivor = config.getBoolean("game.end-on-last-survivor", true);
        killsToWin = config.getInt("game.kills-to-win", 10);
        deathsToSpectate = config.getInt("game.deaths-to-spectate", 10);
        defaultMaxMatchSeconds = config.getInt("game.default-max-match-seconds", 0);
        lavaFlowSpeedMultiplier = config.getDouble("game.lava-flow-speed-multiplier", 1.5);
        matchHealMultiplier = config.getDouble("game.match-heal-multiplier", 2.0);
        borderShrinkDurationSeconds = config.getInt("game.border-shrink.duration-seconds", 90);
        borderShrinkPerTick = config.getDouble("game.border-shrink.shrink-per-tick", 0.5);
        stripCustomOnMatchEnd = config.getBoolean("loot.strip-custom-on-match-end", true);
        customItemGlobalWeight = config.getInt("loot.custom-item-global-weight", 5);
        lootBoxMinDistanceFromCenter = config.getInt("loot.boxes.min-distance-from-center", 0);
        lootBoxMinSpacing = config.getInt("loot.boxes.min-spacing", 8);
        lootBoxDensityPer100Radius = config.getDouble("loot.boxes.density-per-100-radius", 6);
        densityMultiplierLow = config.getDouble("loot.density-multipliers.low", 0.5);
        densityMultiplierMedium = config.getDouble("loot.density-multipliers.medium", 1.0);
        densityMultiplierHigh = config.getDouble("loot.density-multipliers.high", 1.75);
        messagePrefix = config.getString("messages.prefix", "&6[HG] &r");

        String blockType = config.getString("loot.boxes.block-type", "CHEST");
        Material material = Material.matchMaterial(blockType);
        lootBoxBlockType = material != null ? material : Material.CHEST;

        powerUpsEnabled = config.getBoolean("powerups.enabled", true);
        powerUpMinIntervalSeconds = config.getInt("powerups.min-interval-seconds", 9);
        powerUpMaxIntervalSeconds = config.getInt("powerups.max-interval-seconds", 18);
        powerUpDespawnSeconds = config.getInt("powerups.despawn-seconds", 90);
        powerUpPickupRadius = config.getDouble("powerups.pickup-radius", 2.0);
        powerUpDropHeightBlocks = config.getInt("powerups.drop-height-blocks", 25);
        powerUpMinEffectDurationSeconds = config.getInt("powerups.min-effect-duration-seconds", 20);
        powerUpPlayersPerSpawn = config.getInt("powerups.players-per-spawn", 1);
        powerUpMinSpawnsPerWave = config.getInt("powerups.min-spawns-per-wave", 2);
        powerUpMaxSpawnsPerWave = config.getInt("powerups.max-spawns-per-wave", 10);
        powerUpPlayersPerActive = config.getInt("powerups.players-per-active", 2);
        powerUpMaxActiveCap = config.getInt("powerups.max-active-cap", 20);
        powerUpUltimateSpawnChance = config.getDouble("powerups.ultimate-spawn-chance", 0.15);
        powerUpBeaconBeamHeightBlocks = config.getInt("powerups.beacon-beam-height-blocks", 80);
        powerUpBeaconBeamIntervalTicks = config.getInt("powerups.beacon-beam-interval-ticks", 4);
    }

    public int getBlocksPerTick() {
        return blocksPerTick;
    }

    public int getWallThickness() {
        return wallThickness;
    }

    public int getBaseTerrainY() {
        return baseTerrainY;
    }

    public int getDefaultWallHeight() {
        return defaultWallHeight;
    }

    public int getDefaultCountdown() {
        return defaultCountdown;
    }

    public int getDefaultGracePeriod() {
        return defaultGracePeriod;
    }

    public int getRespawnGraceSeconds() {
        return respawnGraceSeconds;
    }

    public int getMinPlayersDefault() {
        return minPlayersDefault;
    }

    public int getEndingDelaySeconds() {
        return endingDelaySeconds;
    }

    public boolean isEndOnLastSurvivor() {
        return endOnLastSurvivor;
    }

    public int getKillsToWin() {
        return killsToWin;
    }

    public int getDeathsToSpectate() {
        return deathsToSpectate;
    }

    public int getDefaultMaxMatchSeconds() {
        return defaultMaxMatchSeconds;
    }

    public double getLavaFlowSpeedMultiplier() {
        return lavaFlowSpeedMultiplier;
    }

    public double getMatchHealMultiplier() {
        return matchHealMultiplier;
    }

    public int getBorderShrinkDurationSeconds() {
        return borderShrinkDurationSeconds;
    }

    public double getBorderShrinkPerTick() {
        return borderShrinkPerTick;
    }

    public boolean isStripCustomOnMatchEnd() {
        return stripCustomOnMatchEnd;
    }

    public int getCustomItemGlobalWeight() {
        return customItemGlobalWeight;
    }

    public Material getLootBoxBlockType() {
        return lootBoxBlockType;
    }

    public int getLootBoxMinDistanceFromCenter() {
        return lootBoxMinDistanceFromCenter;
    }

    public int getLootBoxMinSpacing() {
        return lootBoxMinSpacing;
    }

    public double getLootBoxDensityPer100Radius() {
        return lootBoxDensityPer100Radius;
    }

    public double getDensityMultiplier(String density) {
        return switch (density.toLowerCase()) {
            case "low" -> densityMultiplierLow;
            case "high" -> densityMultiplierHigh;
            default -> densityMultiplierMedium;
        };
    }

    public String getMessagePrefix() {
        return messagePrefix;
    }

    public boolean isPowerUpsEnabled() {
        return powerUpsEnabled;
    }

    public int getPowerUpMinIntervalSeconds() {
        return powerUpMinIntervalSeconds;
    }

    public int getPowerUpMaxIntervalSeconds() {
        return powerUpMaxIntervalSeconds;
    }

    public int getPowerUpDespawnSeconds() {
        return powerUpDespawnSeconds;
    }

    public double getPowerUpPickupRadius() {
        return powerUpPickupRadius;
    }

    public int getPowerUpDropHeightBlocks() {
        return powerUpDropHeightBlocks;
    }

    public int getPowerUpMinEffectDurationSeconds() {
        return powerUpMinEffectDurationSeconds;
    }

    public int getPowerUpPlayersPerSpawn() {
        return powerUpPlayersPerSpawn;
    }

    public int getPowerUpMinSpawnsPerWave() {
        return powerUpMinSpawnsPerWave;
    }

    public int getPowerUpMaxSpawnsPerWave() {
        return powerUpMaxSpawnsPerWave;
    }

    public int getPowerUpPlayersPerActive() {
        return powerUpPlayersPerActive;
    }

    public int getPowerUpMaxActiveCap() {
        return powerUpMaxActiveCap;
    }

    public double getPowerUpUltimateSpawnChance() {
        return powerUpUltimateSpawnChance;
    }

    public int getPowerUpBeaconBeamHeightBlocks() {
        return powerUpBeaconBeamHeightBlocks;
    }

    public int getPowerUpBeaconBeamIntervalTicks() {
        return powerUpBeaconBeamIntervalTicks;
    }
}
