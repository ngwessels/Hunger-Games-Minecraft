package com.hungergames.arena;

import com.hungergames.HungerGamesPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class ArenaManager {

    private final HungerGamesPlugin plugin;
    private final Map<String, Arena> arenas = new LinkedHashMap<>();
    private File arenasFile;

    public ArenaManager(HungerGamesPlugin plugin) {
        this.plugin = plugin;
        load();
    }

    public void load() {
        arenas.clear();
        arenasFile = new File(plugin.getDataFolder(), "arenas.yml");
        if (!arenasFile.exists()) {
            plugin.saveResource("arenas.yml", false);
        }
        FileConfiguration config = YamlConfiguration.loadConfiguration(arenasFile);
        ConfigurationSection section = config.getConfigurationSection("arenas");
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            ConfigurationSection arenaSection = section.getConfigurationSection(key);
            if (arenaSection == null) {
                continue;
            }
            Arena arena = new Arena(key);
            arena.setWorldName(arenaSection.getString("world", arena.getWorldName()));
            arena.setCenter(
                    arenaSection.getDouble("center.x", Arena.DEFAULT_CENTER_X),
                    arenaSection.getDouble("center.y", plugin.getPluginConfig().getBaseTerrainY()),
                    arenaSection.getDouble("center.z", Arena.DEFAULT_CENTER_Z)
            );
            migrateLegacyHeights(arena);
            arena.setRadius(arenaSection.getInt("radius"));
            arena.setWallHeight(arenaSection.getInt("wallHeight", plugin.getPluginConfig().getDefaultWallHeight()));
            arena.setGameModeId(arenaSection.getString("gameMode", "standard"));
            arena.setGenerated(arenaSection.getBoolean("generated", false));
            if (arenaSection.contains("terrainSeed")) {
                arena.setTerrainSeed(arenaSection.getInt("terrainSeed"));
            }

            List<?> spawns = arenaSection.getList("spawns");
            if (spawns != null) {
                for (Object obj : spawns) {
                    if (obj instanceof Map<?, ?> map) {
                        double x = toDouble(map.get("x"));
                        double y = toDouble(map.get("y"));
                        double z = toDouble(map.get("z"));
                        World world = Bukkit.getWorld(arena.getWorldName());
                        if (world != null) {
                            arena.getSpawnPoints().add(new Location(world, x, y, z));
                        }
                    }
                }
            }

            ConfigurationSection centerSpawn = arenaSection.getConfigurationSection("centerSpawn");
            if (centerSpawn != null) {
                World world = Bukkit.getWorld(arena.getWorldName());
                if (world != null) {
                    arena.setCenterSpawn(new Location(
                            world,
                            centerSpawn.getDouble("x"),
                            centerSpawn.getDouble("y"),
                            centerSpawn.getDouble("z")
                    ));
                }
            }

            arenas.put(key.toLowerCase(), arena);
        }
    }

    private void migrateLegacyHeights(Arena arena) {
        if (arena.getCenterY() == Arena.LEGACY_CENTER_Y) {
            arena.setCenter(arena.getCenterX(), plugin.getPluginConfig().getBaseTerrainY(), arena.getCenterZ());
        }
        if (arena.getWallHeight() == Arena.LEGACY_WALL_HEIGHT) {
            arena.setWallHeight(plugin.getPluginConfig().getDefaultWallHeight());
        }
    }

    private double toDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return 0;
    }

    public void save() {
        FileConfiguration config = new YamlConfiguration();
        for (Arena arena : arenas.values()) {
            String path = "arenas." + arena.getName();
            config.set(path + ".world", arena.getWorldName());
            config.set(path + ".center.x", arena.getCenterX());
            config.set(path + ".center.y", arena.getCenterY());
            config.set(path + ".center.z", arena.getCenterZ());
            config.set(path + ".radius", arena.getRadius());
            config.set(path + ".wallHeight", arena.getWallHeight());
            config.set(path + ".gameMode", arena.getGameModeId());
            config.set(path + ".generated", arena.isGenerated());
            config.set(path + ".terrainSeed", arena.getTerrainSeed());

            List<Map<String, Double>> spawns = arena.getSpawnPoints().stream()
                    .map(loc -> Map.of(
                            "x", loc.getX(),
                            "y", loc.getY(),
                            "z", loc.getZ()
                    ))
                    .toList();
            config.set(path + ".spawns", spawns);

            if (arena.getCenterSpawn() != null) {
                Location cs = arena.getCenterSpawn();
                config.set(path + ".centerSpawn.x", cs.getX());
                config.set(path + ".centerSpawn.y", cs.getY());
                config.set(path + ".centerSpawn.z", cs.getZ());
            }
        }

        try {
            config.save(arenasFile);
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to save arenas.yml: " + e.getMessage());
        }
    }

    public Arena createArena(String name, int radius, int wallHeight) {
        Arena arena = new Arena(name);
        arena.setCenter(
                Arena.DEFAULT_CENTER_X,
                plugin.getPluginConfig().getBaseTerrainY(),
                Arena.DEFAULT_CENTER_Z);
        arena.setRadius(radius);
        arena.setWallHeight(wallHeight);
        arenas.put(name.toLowerCase(), arena);
        save();
        return arena;
    }

    public Optional<Arena> getArena(String name) {
        return Optional.ofNullable(arenas.get(name.toLowerCase()));
    }

    public Collection<Arena> getArenas() {
        return Collections.unmodifiableCollection(arenas.values());
    }

    public boolean deleteArena(String name) {
        Arena arena = arenas.remove(name.toLowerCase());
        if (arena == null) {
            return false;
        }
        World world = arena.getWorld();
        if (world != null) {
            Bukkit.unloadWorld(world, false);
            File worldFolder = new File(Bukkit.getWorldContainer(), world.getName());
            deleteFolder(worldFolder);
        }
        save();
        return true;
    }

    private void deleteFolder(File folder) {
        if (!folder.exists()) {
            return;
        }
        File[] files = folder.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    deleteFolder(file);
                } else {
                    file.delete();
                }
            }
        }
        folder.delete();
    }

    public World getOrCreateWorld(Arena arena) {
        World existing = arena.getWorld();
        if (existing != null) {
            return existing;
        }
        WorldCreator creator = new WorldCreator(arena.getWorldName());
        creator.generator(new VoidChunkGenerator());
        creator.environment(World.Environment.NORMAL);
        return creator.createWorld();
    }
}
