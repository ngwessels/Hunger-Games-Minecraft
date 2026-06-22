package com.hungergames.gamemode;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;

public class TreePlacementConfig {

    private boolean enabled;
    private int cellSize = 5;
    private double clusterThreshold = 0.04;
    private double fullDensityCluster = 0.30;
    private double minDensityChance = 0.30;
    private double maxDensityChance = 0.90;
    private double infertileSoilChanceScale = 0.30;
    private int minHeight = 4;
    private int maxHeight = 8;
    private int excludeCenterRadius;
    private boolean requireFlat = true;
    private final List<TreeSpecies> species = new ArrayList<>();

    public TreePlacementConfig() {
        species.add(new TreeSpecies(Material.OAK_LOG, Material.OAK_LEAVES, 1.0));
        species.add(new TreeSpecies(Material.SPRUCE_LOG, Material.SPRUCE_LEAVES, 1.0));
        species.add(new TreeSpecies(Material.BIRCH_LOG, Material.BIRCH_LEAVES, 1.0));
    }

    public static TreePlacementConfig fromYaml(ConfigurationSection section) {
        TreePlacementConfig config = new TreePlacementConfig();
        if (section == null) {
            return config;
        }
        config.enabled = section.getBoolean("enabled", true);
        config.cellSize = section.getInt("cellSize", 5);
        config.clusterThreshold = section.getDouble("clusterThreshold", 0.04);
        config.fullDensityCluster = section.getDouble("fullDensityCluster", 0.30);
        config.minDensityChance = section.getDouble("minDensityChance", 0.30);
        config.maxDensityChance = section.getDouble("maxDensityChance", 0.90);
        config.infertileSoilChanceScale = section.getDouble("infertileSoilChanceScale", 0.30);
        config.minHeight = section.getInt("minHeight", 4);
        config.maxHeight = section.getInt("maxHeight", 8);
        config.excludeCenterRadius = section.getInt("excludeCenterRadius", 0);
        config.requireFlat = section.getBoolean("requireFlat", true);

        List<String> speciesNames = section.getStringList("species");
        if (!speciesNames.isEmpty()) {
            config.species.clear();
            for (String name : speciesNames) {
                TreeSpecies parsed = TreeSpecies.parse(name);
                if (parsed != null) {
                    config.species.add(parsed);
                }
            }
        }
        return config;
    }

    public static TreePlacementConfig forInitialTerrain(String terrain) {
        return switch (terrain.toLowerCase()) {
            case "volcano" -> forVolcano();
            case "barren" -> forBarren();
            case "mixed" -> forMixed();
            default -> forForest();
        };
    }

    public static TreePlacementConfig forVolcano() {
        TreePlacementConfig config = new TreePlacementConfig();
        config.enabled = true;
        config.cellSize = 5;
        config.clusterThreshold = 0.04;
        config.fullDensityCluster = 0.30;
        config.minDensityChance = 0.30;
        config.maxDensityChance = 0.90;
        config.minHeight = 4;
        config.maxHeight = 8;
        config.excludeCenterRadius = 10;
        return config;
    }

    public static TreePlacementConfig forForest() {
        TreePlacementConfig config = new TreePlacementConfig();
        config.enabled = true;
        config.cellSize = 5;
        config.clusterThreshold = 0.04;
        return config;
    }

    public static TreePlacementConfig forMixed() {
        TreePlacementConfig config = new TreePlacementConfig();
        config.enabled = true;
        config.cellSize = 7;
        config.clusterThreshold = 0.10;
        config.maxDensityChance = 0.55;
        config.species.clear();
        config.species.add(new TreeSpecies(Material.OAK_LOG, Material.OAK_LEAVES, 1.0));
        config.species.add(new TreeSpecies(Material.BIRCH_LOG, Material.BIRCH_LEAVES, 1.0));
        return config;
    }

    public static TreePlacementConfig forBarren() {
        TreePlacementConfig config = new TreePlacementConfig();
        config.enabled = true;
        config.cellSize = 8;
        config.clusterThreshold = 0.12;
        config.minHeight = 3;
        config.maxHeight = 5;
        config.maxDensityChance = 0.45;
        config.species.clear();
        config.species.add(new TreeSpecies(Material.OAK_LOG, Material.OAK_LEAVES, 1.0));
        config.species.add(new TreeSpecies(Material.SPRUCE_LOG, Material.SPRUCE_LEAVES, 1.0));
        return config;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getCellSize() {
        return cellSize;
    }

    public double getClusterThreshold() {
        return clusterThreshold;
    }

    public double getFullDensityCluster() {
        return fullDensityCluster;
    }

    public double getMinDensityChance() {
        return minDensityChance;
    }

    public double getMaxDensityChance() {
        return maxDensityChance;
    }

    public double getInfertileSoilChanceScale() {
        return infertileSoilChanceScale;
    }

    public int getMinHeight() {
        return minHeight;
    }

    public int getMaxHeight() {
        return maxHeight;
    }

    public int getExcludeCenterRadius() {
        return excludeCenterRadius;
    }

    public boolean isRequireFlat() {
        return requireFlat;
    }

    public List<TreeSpecies> getSpecies() {
        return species;
    }

    public record TreeSpecies(Material log, Material leaves, double weight) {
        public static TreeSpecies parse(String name) {
            return switch (name.toLowerCase()) {
                case "oak" -> new TreeSpecies(Material.OAK_LOG, Material.OAK_LEAVES, 1.0);
                case "spruce" -> new TreeSpecies(Material.SPRUCE_LOG, Material.SPRUCE_LEAVES, 1.0);
                case "birch" -> new TreeSpecies(Material.BIRCH_LOG, Material.BIRCH_LEAVES, 1.0);
                default -> null;
            };
        }
    }
}
