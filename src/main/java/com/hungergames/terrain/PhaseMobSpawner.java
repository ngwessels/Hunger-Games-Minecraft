package com.hungergames.terrain;

import com.hungergames.HungerGamesPlugin;
import com.hungergames.arena.Arena;
import com.hungergames.gamemode.TerrainPhaseConfig;
import com.hungergames.util.ArenaEntityUtil;
import com.hungergames.util.CircleUtil;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public final class PhaseMobSpawner {

    private PhaseMobSpawner() {
    }

    public static void trySpawn(HungerGamesPlugin plugin, World world, Arena arena,
                                  TerrainPhaseConfig config, double intensity, Random random) {
        int maxMobs = config.getInt("maxHostileMobs", 4);
        if (ArenaEntityUtil.countPhaseMobs(world, arena) >= maxMobs) {
            return;
        }

        double minIntensity = config.getDouble("mobSpawnMinIntensity", 0.2);
        if (intensity < minIntensity) {
            return;
        }

        double chance = config.getDouble("mobSpawnChancePerTick", 0.04);
        if (random.nextDouble() > chance * (0.5 + intensity)) {
            return;
        }

        EntityType type = pickMobType(config, intensity, random);
        if (type == null) {
            return;
        }

        int spawnMin = config.getInt("mobSpawnRadiusMin", 8);
        int spawnMax = config.getInt("mobSpawnRadiusMax", arena.getRadius() - 4);
        Location spawn = findSpawnLocation(world, arena, spawnMin, spawnMax, random);
        if (spawn == null) {
            return;
        }

        LivingEntity mob = (LivingEntity) world.spawnEntity(spawn, type);
        ArenaEntityUtil.tagPhaseMob(plugin, mob);
    }

    private static EntityType pickMobType(TerrainPhaseConfig config, double intensity, Random random) {
        List<String> early = getMobList(config, "mobTypesEarly");
        List<String> late = getMobList(config, "mobTypesLate");
        List<String> pool = (intensity >= config.getDouble("mobLateIntensityThreshold", 0.55) && !late.isEmpty())
                ? late : early;
        if (pool.isEmpty()) {
            pool = getMobList(config, "mobTypes");
        }
        if (pool.isEmpty()) {
            return null;
        }
        String name = pool.get(random.nextInt(pool.size()));
        try {
            return EntityType.valueOf(name.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> getMobList(TerrainPhaseConfig config, String key) {
        Object raw = config.getParams().get(key);
        List<String> result = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                result.add(String.valueOf(item));
            }
        }
        return result;
    }

    private static Location findSpawnLocation(World world, Arena arena, int minRadius, int maxRadius,
                                              Random random) {
        double cx = arena.getCenterX();
        double cz = arena.getCenterZ();
        int baseY = (int) arena.getCenterY();

        for (int attempt = 0; attempt < 16; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double dist = minRadius + random.nextDouble() * Math.max(1, maxRadius - minRadius);
            int x = (int) (cx + Math.cos(angle) * dist);
            int z = (int) (cz + Math.sin(angle) * dist);
            if (!CircleUtil.isInsideCircle(cx, cz, x, z, arena.getRadius() - 2)) {
                continue;
            }
            int y = findSurfaceY(world, x, baseY, z);
            Block block = world.getBlockAt(x, y, z);
            Block above = world.getBlockAt(x, y + 1, z);
            if (!block.getType().isSolid() || !above.getType().isAir()) {
                continue;
            }
            Material below = block.getType();
            if (below == Material.LAVA || below == Material.WATER) {
                continue;
            }
            return new Location(world, x + 0.5, y + 1, z + 0.5);
        }
        return null;
    }

    private static int findSurfaceY(World world, int x, int baseY, int z) {
        for (int y = baseY + 15; y >= baseY - 8; y--) {
            if (world.getBlockAt(x, y, z).getType().isSolid()) {
                return y;
            }
        }
        return baseY;
    }
}
