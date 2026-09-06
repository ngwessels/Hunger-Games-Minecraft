package com.hungergames.arena;

import com.hungergames.HungerGamesPlugin;
import com.hungergames.util.ArenaEntityUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;

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
            arena.setGameModeId(arenaSection.getString("gameMode", "standard"));
            arena.setRadius(arenaSection.getInt("radius", Arena.RADIUS));
            arena.setWallHeight(Arena.WALL_HEIGHT);
            arena.setGenerated(arenaSection.getBoolean("generated", false));
            if (arenaSection.contains("terrainSeed")) {
                arena.setTerrainSeed(arenaSection.getInt("terrainSeed"));
            }

            List<?> spawns = arenaSection.getList("spawns");
            if (spawns != null) {
                World world = Bukkit.getWorld(arena.getWorldName());
                for (Object obj : spawns) {
                    if (obj instanceof Map<?, ?> map) {
                        arena.getSpawnPoints().add(new Location(
                                world,
                                toDouble(map.get("x")),
                                toDouble(map.get("y")),
                                toDouble(map.get("z"))
                        ));
                    }
                }
            }

            ConfigurationSection centerSpawn = arenaSection.getConfigurationSection("centerSpawn");
            if (centerSpawn != null) {
                World world = Bukkit.getWorld(arena.getWorldName());
                arena.setCenterSpawn(new Location(
                        world,
                        centerSpawn.getDouble("x"),
                        centerSpawn.getDouble("y"),
                        centerSpawn.getDouble("z")
                ));
            }

            arenas.put(key.toLowerCase(), arena);
        }
    }

    private void migrateLegacyHeights(Arena arena) {
        if (arena.getCenterY() == Arena.LEGACY_CENTER_Y) {
            arena.setCenter(arena.getCenterX(), plugin.getPluginConfig().getBaseTerrainY(), arena.getCenterZ());
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
            config.set(path + ".wallHeight", Arena.WALL_HEIGHT);
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

    public Arena createArena(String name) {
        Arena arena = new Arena(name);
        arena.setCenter(
                Arena.DEFAULT_CENTER_X,
                plugin.getPluginConfig().getBaseTerrainY(),
                Arena.DEFAULT_CENTER_Z);
        arena.setRadius(Arena.RADIUS);
        arena.setWallHeight(Arena.WALL_HEIGHT);
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
            evictPlayers(world);
            Bukkit.unloadWorld(world, false);
        }
        try {
            ArenaWorldCopy.deleteRecursively(getPlayWorldFolder(arena));
            ArenaWorldCopy.deleteRecursively(getTemplateWorldFolder(arena));
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to delete arena world folders for " + name, e);
        }
        save();
        return true;
    }

    public File getPlayWorldFolder(Arena arena) {
        return new File(Bukkit.getWorldContainer(), arena.getWorldName());
    }

    public File getTemplateWorldFolder(Arena arena) {
        return new File(Bukkit.getWorldContainer(), arena.getWorldName() + "_template");
    }

    public boolean hasTemplate(Arena arena) {
        return new File(getTemplateWorldFolder(arena), "level.dat").isFile();
    }

    public World getOrCreateWorld(Arena arena) {
        World existing = arena.getWorld();
        if (existing != null) {
            ArenaEntityUtil.suppressVanillaMonsterSpawning(existing);
            bindWorldLocations(arena, existing);
            return existing;
        }
        recoverPlayWorldFromTemplateIfNeeded(arena);
        WorldCreator creator = new WorldCreator(arena.getWorldName());
        creator.generator(new VoidChunkGenerator());
        creator.environment(World.Environment.NORMAL);
        World world = creator.createWorld();
        ArenaEntityUtil.suppressVanillaMonsterSpawning(world);
        bindWorldLocations(arena, world);
        return world;
    }

    private void recoverPlayWorldFromTemplateIfNeeded(Arena arena) {
        File play = getPlayWorldFolder(arena);
        if (new File(play, "level.dat").isFile() || !hasTemplate(arena)) {
            return;
        }
        try {
            ArenaWorldCopy.deleteRecursively(play);
            ArenaWorldCopy.copyDirectory(getTemplateWorldFolder(arena), play);
            plugin.getLogger().info("Recovered arena world " + arena.getWorldName() + " from template.");
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to recover arena world from template", e);
        }
    }

    public void bindWorldLocations(Arena arena, World world) {
        if (world == null) {
            return;
        }
        List<Location> rebound = new ArrayList<>();
        for (Location loc : arena.getSpawnPoints()) {
            rebound.add(new Location(world, loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch()));
        }
        arena.setSpawnPoints(rebound);
        if (arena.getCenterSpawn() != null) {
            Location cs = arena.getCenterSpawn();
            arena.setCenterSpawn(new Location(world, cs.getX(), cs.getY(), cs.getZ(), cs.getYaw(), cs.getPitch()));
        }
    }

    /**
     * Unloads the play world, copies it to {@code <world>_template}, then reloads
     * the play world. Call after a clean generation so later resets can swap folders.
     */
    public void captureTemplate(Arena arena, Consumer<Boolean> done) {
        World world = arena.getWorld();
        if (world == null) {
            complete(done, false);
            return;
        }
        List<EvictedPlayer> evicted = evictPlayers(world);
        world.save();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!unloadPlayWorld(arena, true)) {
                plugin.getLogger().warning("Could not unload " + arena.getWorldName() + " to save template.");
                restoreEvicted(getOrCreateWorld(arena), evicted);
                complete(done, false);
                return;
            }
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                boolean copied = copyPlayToTemplate(arena);
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    World loaded = getOrCreateWorld(arena);
                    restoreEvicted(loaded, evicted);
                    complete(done, copied && loaded != null);
                });
            });
        }, 1L);
    }

    /**
     * Unloads the used play world without saving, deletes it, and copies the
     * template folder back so the next match starts from the original generation.
     */
    public void restoreFromTemplate(Arena arena, Consumer<Boolean> done) {
        if (!hasTemplate(arena)) {
            complete(done, false);
            return;
        }
        World world = arena.getWorld();
        List<EvictedPlayer> evicted = world != null ? evictPlayers(world) : List.of();
        Runnable copyAndLoad = () -> plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean copied = copyTemplateToPlay(arena);
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                World loaded = getOrCreateWorld(arena);
                restoreEvicted(loaded, evicted);
                complete(done, copied && loaded != null);
            });
        });
        if (world == null) {
            copyAndLoad.run();
            return;
        }
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!unloadPlayWorld(arena, false)) {
                plugin.getLogger().warning("Could not unload " + arena.getWorldName() + " to restore template.");
                restoreEvicted(arena.getWorld(), evicted);
                complete(done, false);
                return;
            }
            copyAndLoad.run();
        }, 1L);
    }

    private boolean copyPlayToTemplate(Arena arena) {
        File play = getPlayWorldFolder(arena);
        File template = getTemplateWorldFolder(arena);
        try {
            ArenaWorldCopy.deleteRecursively(template);
            ArenaWorldCopy.copyDirectory(play, template);
            plugin.getLogger().info("Saved arena template for " + arena.getName() + ".");
            return true;
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to save arena template for " + arena.getName(), e);
            return false;
        }
    }

    private boolean copyTemplateToPlay(Arena arena) {
        File play = getPlayWorldFolder(arena);
        File template = getTemplateWorldFolder(arena);
        File discarded = new File(play.getParentFile(), play.getName() + "_discarding");
        try {
            ArenaWorldCopy.deleteRecursively(discarded);
            if (play.exists() && !play.renameTo(discarded)) {
                ArenaWorldCopy.deleteRecursively(play);
            }
            ArenaWorldCopy.copyDirectory(template, play);
            ArenaWorldCopy.deleteRecursively(discarded);
            plugin.getLogger().info("Restored arena " + arena.getName() + " from template.");
            return true;
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to restore arena from template for " + arena.getName(), e);
            return false;
        }
    }

    private boolean unloadPlayWorld(Arena arena, boolean save) {
        World world = arena.getWorld();
        if (world == null) {
            return true;
        }
        evictPlayers(world);
        return Bukkit.unloadWorld(world, save);
    }

    private List<EvictedPlayer> evictPlayers(World world) {
        List<EvictedPlayer> evicted = new ArrayList<>();
        if (world == null) {
            return evicted;
        }
        World fallback = fallbackWorld(world);
        Location dest = fallback != null ? fallback.getSpawnLocation() : null;
        for (Player player : List.copyOf(world.getPlayers())) {
            evicted.add(new EvictedPlayer(
                    player.getUniqueId(),
                    player.getLocation().getX(),
                    player.getLocation().getY(),
                    player.getLocation().getZ(),
                    player.getLocation().getYaw(),
                    player.getLocation().getPitch()
            ));
            if (dest != null) {
                player.teleport(dest);
            }
        }
        return evicted;
    }

    private void restoreEvicted(World world, List<EvictedPlayer> evicted) {
        if (world == null || evicted == null || evicted.isEmpty()) {
            return;
        }
        for (EvictedPlayer evictedPlayer : evicted) {
            Player player = Bukkit.getPlayer(evictedPlayer.uuid());
            if (player == null || !player.isOnline()) {
                continue;
            }
            player.teleport(new Location(
                    world,
                    evictedPlayer.x(),
                    evictedPlayer.y(),
                    evictedPlayer.z(),
                    evictedPlayer.yaw(),
                    evictedPlayer.pitch()
            ));
        }
    }

    private World fallbackWorld(World exclude) {
        for (World world : Bukkit.getWorlds()) {
            if (!world.equals(exclude)) {
                return world;
            }
        }
        return null;
    }

    private void complete(Consumer<Boolean> done, boolean success) {
        if (done != null) {
            done.accept(success);
        }
    }

    private record EvictedPlayer(UUID uuid, double x, double y, double z, float yaw, float pitch) {
    }
}
