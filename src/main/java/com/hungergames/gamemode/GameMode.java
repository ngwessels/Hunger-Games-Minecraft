package com.hungergames.gamemode;

import java.util.ArrayList;
import java.util.List;

public class GameMode {

    private final String id;
    private String displayName;
    private Difficulty difficulty;
    private String description;
    private int minPlayers;
    private int maxPlayers;
    private int gracePeriodSeconds;
    private int maxMatchSeconds;
    private String lootTier;
    private String lootBoxDensity;
    private String initialTerrain;
    private boolean borderShrinkEnabled;
    private TreePlacementConfig treePlacement;
    private final List<TerrainPhaseConfig> terrainPhases = new ArrayList<>();

    public GameMode(String id) {
        this.id = id;
    }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public Difficulty getDifficulty() {
        return difficulty;
    }

    public void setDifficulty(Difficulty difficulty) {
        this.difficulty = difficulty;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public int getMinPlayers() {
        return minPlayers;
    }

    public void setMinPlayers(int minPlayers) {
        this.minPlayers = minPlayers;
    }

    public int getMaxPlayers() {
        return maxPlayers;
    }

    public void setMaxPlayers(int maxPlayers) {
        this.maxPlayers = maxPlayers;
    }

    public int getGracePeriodSeconds() {
        return gracePeriodSeconds;
    }

    public void setGracePeriodSeconds(int gracePeriodSeconds) {
        this.gracePeriodSeconds = gracePeriodSeconds;
    }

    public int getMaxMatchSeconds() {
        return maxMatchSeconds;
    }

    public void setMaxMatchSeconds(int maxMatchSeconds) {
        this.maxMatchSeconds = maxMatchSeconds;
    }

    public String getLootTier() {
        return lootTier;
    }

    public void setLootTier(String lootTier) {
        this.lootTier = lootTier;
    }

    public String getLootBoxDensity() {
        return lootBoxDensity;
    }

    public void setLootBoxDensity(String lootBoxDensity) {
        this.lootBoxDensity = lootBoxDensity;
    }

    public String getInitialTerrain() {
        return initialTerrain;
    }

    public void setInitialTerrain(String initialTerrain) {
        this.initialTerrain = initialTerrain;
    }

    public boolean isBorderShrinkEnabled() {
        return borderShrinkEnabled;
    }

    public void setBorderShrinkEnabled(boolean borderShrinkEnabled) {
        this.borderShrinkEnabled = borderShrinkEnabled;
    }

    public TreePlacementConfig getTreePlacement() {
        return treePlacement;
    }

    public void setTreePlacement(TreePlacementConfig treePlacement) {
        this.treePlacement = treePlacement;
    }

    public List<TerrainPhaseConfig> getTerrainPhases() {
        return terrainPhases;
    }
}
