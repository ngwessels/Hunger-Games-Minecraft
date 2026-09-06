package com.hungergames.gamemode;

import com.hungergames.HungerGamesPlugin;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class GameModeRegistry {

    private final HungerGamesPlugin plugin;
    private final Map<String, GameMode> modes = new HashMap<>();

    public GameModeRegistry(HungerGamesPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        modes.clear();
        File folder = new File(plugin.getDataFolder(), "gamemodes");
        if (!folder.exists()) {
            folder.mkdirs();
            copyDefaultGamemodes();
        }

        File[] files = folder.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null) {
            return;
        }

        for (File file : files) {
            YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
            // Stale on-disk files predate newer sections (e.g. settlements);
            // merge the shipped defaults so new features activate without
            // wiping any settings the user customized.
            copyDefaultsForMissingSections(file, config);
            String id = config.getString("id", file.getName().replace(".yml", ""));
            GameMode mode = new GameMode(id);
            mode.setDisplayName(config.getString("displayName", id));
            mode.setDescription(config.getString("description", ""));
            mode.setMinPlayers(config.getInt("minPlayers", 1));
            mode.setMaxPlayers(config.getInt("maxPlayers", 24));
            mode.setGracePeriodSeconds(config.getInt("gracePeriodSeconds", 30));
            mode.setMaxMatchSeconds(config.getInt("maxMatchSeconds", 0));
            mode.setLootTier(config.getString("lootTier", "normal"));
            mode.setLootBoxDensity(config.getString("lootBoxDensity", "medium"));
            mode.setInitialTerrain(config.getString("initialTerrain", "forest"));
            mode.setArenaRadius(config.getInt("arenaRadius", 300));

            try {
                mode.setDifficulty(Difficulty.valueOf(config.getString("difficulty", "NORMAL").toUpperCase()));
            } catch (IllegalArgumentException e) {
                mode.setDifficulty(Difficulty.NORMAL);
            }

            boolean borderShrinkEnabled = parseBorderShrinkEnabled(config);
            mode.setBorderShrinkEnabled(borderShrinkEnabled);

            List<Map<?, ?>> phases = config.getMapList("terrainPhases");
            for (Map<?, ?> phaseMap : phases) {
                String type = String.valueOf(phaseMap.get("type"));
                if ("BORDER_SHRINK".equalsIgnoreCase(type) && !borderShrinkEnabled) {
                    continue;
                }
                int duration = phaseMap.containsKey("durationSeconds")
                        ? ((Number) phaseMap.get("durationSeconds")).intValue() : 60;
                Map<String, Object> params = new HashMap<>();
                for (Map.Entry<?, ?> entry : phaseMap.entrySet()) {
                    String key = String.valueOf(entry.getKey());
                    if (!key.equals("type") && !key.equals("durationSeconds")) {
                        params.put(key, entry.getValue());
                    }
                }
                mode.getTerrainPhases().add(new TerrainPhaseConfig(type, duration, params));
            }

            if (borderShrinkEnabled && !hasBorderShrinkPhase(mode)) {
                mode.getTerrainPhases().add(createBorderShrinkPhase(config));
            }

            ConfigurationSection treeSection = config.getConfigurationSection("treePlacement");
            if (treeSection != null) {
                mode.setTreePlacement(TreePlacementConfig.fromYaml(treeSection));
            } else {
                mode.setTreePlacement(TreePlacementConfig.forInitialTerrain(mode.getInitialTerrain()));
            }

            mode.setJungleGrove(JungleGroveConfig.fromYaml(config.getConfigurationSection("jungleGrove")));
            mode.setPonds(PondConfig.fromYaml(config.getConfigurationSection("ponds")));
            mode.setSettlement(SettlementConfig.fromYaml(config.getConfigurationSection("settlements")));
            mode.setRage(RageConfig.fromYaml(config.getConfigurationSection("rage")));
            mode.setArctic(ArcticConfig.fromYaml(config.getConfigurationSection("arctic")));
            mode.setComet(CometConfig.fromYaml(config.getConfigurationSection("comet")));

            modes.put(id.toLowerCase(), mode);
        }

        plugin.getLogger().info("Loaded " + modes.size() + " game modes.");
    }

    private boolean parseBorderShrinkEnabled(YamlConfiguration config) {
        if (config.isBoolean("borderShrink")) {
            return config.getBoolean("borderShrink");
        }
        ConfigurationSection section = config.getConfigurationSection("borderShrink");
        if (section != null) {
            return section.getBoolean("enabled", false);
        }
        return false;
    }

    private boolean hasBorderShrinkPhase(GameMode mode) {
        return mode.getTerrainPhases().stream()
                .anyMatch(phase -> "BORDER_SHRINK".equalsIgnoreCase(phase.getType()));
    }

    private TerrainPhaseConfig createBorderShrinkPhase(YamlConfiguration config) {
        ConfigurationSection section = config.getConfigurationSection("borderShrink");
        int duration = plugin.getPluginConfig().getBorderShrinkDurationSeconds();
        double shrinkPerTick = plugin.getPluginConfig().getBorderShrinkPerTick();
        if (section != null) {
            duration = section.getInt("durationSeconds", duration);
            shrinkPerTick = section.getDouble("shrinkPerTick", shrinkPerTick);
        }
        Map<String, Object> params = new HashMap<>();
        params.put("shrinkPerTick", shrinkPerTick);
        return new TerrainPhaseConfig("BORDER_SHRINK", duration, params);
    }

    private void copyDefaultGamemodes() {
        String[] defaults = {
                "peaceful_forest.yml", "standard.yml", "nether_chaos.yml",
                "aquatic_doom.yml", "scorched_earth.yml", "volcano.yml"
        };
        for (String name : defaults) {
            plugin.saveResource("gamemodes/" + name, false);
        }
    }

    /** Copies top-level sections from the shipped jar default that are absent on disk. */
    private void copyDefaultsForMissingSections(File file, YamlConfiguration config) {
        String resourceName = "gamemodes/" + file.getName();
        if (plugin.getResource(resourceName) == null) {
            return;
        }
        try (java.io.InputStream in = plugin.getResource(resourceName)) {
            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                    new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
            boolean changed = false;
            for (String key : defaults.getKeys(true)) {
                if (!key.contains(".") && !config.isSet(key)) {
                    config.set(key, defaults.get(key));
                    changed = true;
                }
            }
            if (changed) {
                config.save(file);
                plugin.getLogger().info("Merged new default sections into " + file.getName()
                        + " (e.g. settlements).");
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Could not merge defaults into " + file.getName()
                    + ": " + e.getMessage());
        }
    }

    public Optional<GameMode> getMode(String id) {
        return Optional.ofNullable(modes.get(id.toLowerCase()));
    }

    public GameMode getModeOrDefault(String id) {
        return getMode(id).orElseGet(() -> modes.getOrDefault("standard",
                modes.values().stream().findFirst().orElse(new GameMode("standard"))));
    }

    public Collection<GameMode> getModes() {
        return Collections.unmodifiableCollection(modes.values());
    }
}
