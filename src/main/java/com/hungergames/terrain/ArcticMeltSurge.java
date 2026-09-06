package com.hungergames.terrain;

import com.hungergames.HungerGamesPlugin;
import com.hungergames.arena.ArcticLayout;
import com.hungergames.arena.ArcticRing;
import com.hungergames.arena.Arena;
import com.hungergames.game.Game;
import com.hungergames.gamemode.GameMode;
import com.hungergames.gamemode.TerrainPhaseConfig;
import com.hungergames.util.BlockSnapshot;
import com.hungergames.util.CircleUtil;
import com.hungergames.util.SpawnLocationUtil;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.FallingBlock;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Glacier calving (falling ice) plus a short inland water surge that recedes
 * before the lagging lava front converts the arctic melt.
 */
public class ArcticMeltSurge {

    private static final Set<Material> PROTECTED = Set.of(
            Material.BEDROCK, Material.CHEST, Material.TRAPPED_CHEST,
            Material.BARREL, Material.ENDER_CHEST, Material.GOLD_BLOCK, Material.BEACON,
            Material.SPAWNER, Material.IRON_BLOCK, Material.EMERALD_BLOCK,
            Material.DIAMOND_BLOCK, Material.NETHERITE_BLOCK
    );

    private final Set<Long> surgedGlaciers = new HashSet<>();

    public void reset() {
        surgedGlaciers.clear();
    }

    public void tick(HungerGamesPlugin plugin, Game game, Arena arena, TerrainPhaseConfig config,
                     int convertedRadius) {
        GameMode mode = game.getGameMode();
        if (!ArcticRing.isActive(arena, mode)) {
            return;
        }
        World world = arena.getWorld();
        if (world == null) {
            return;
        }
        int inland = Math.max(4, config.getInt("arcticTsunamiInlandBlocks", 16));
        int duration = Math.max(10, config.getInt("arcticTsunamiDurationTicks", 40));
        int fallingIce = Math.max(0, config.getInt("arcticCalvingFallingIce", 5));
        int minHeight = Math.max(1, config.getInt("arcticTsunamiMinHeight", 2));
        int maxHeight = Math.max(minHeight, config.getInt("arcticTsunamiMaxHeight", 10));
        int forest = mode.getForestRadius();
        double cx = arena.getCenterX();
        double cz = arena.getCenterZ();

        for (ArcticLayout.Site glacier : arena.getArcticLayout().getGlacierSites()) {
            long key = (((long) glacier.x()) << 32) ^ (glacier.z() & 0xffffffffL);
            if (surgedGlaciers.contains(key)) {
                continue;
            }
            double dist = CircleUtil.distanceFromCenter(cx, cz, glacier.x(), glacier.z());
            if (convertedRadius < dist - 2) {
                continue;
            }
            surgedGlaciers.add(key);
            int height = rollTsunamiHeight(minHeight, maxHeight);
            double scale = height / (double) minHeight;
            int waveInland = Math.min(forest - 20, (int) Math.round(inland * (0.75 + 0.35 * scale)));
            int waveDuration = Math.min(120, (int) Math.round(duration * (0.85 + 0.2 * scale)));
            int waveIce = fallingIce + Math.max(0, height - minHeight);
            calve(world, glacier, cx, cz, waveIce, height);
            surge(plugin, world, arena, glacier, cx, cz, forest, waveInland, waveDuration, height);
        }
    }

    /**
     * Most surges stay near the minimum; a long tail produces occasional much taller walls.
     */
    private static int rollTsunamiHeight(int minHeight, int maxHeight) {
        if (maxHeight <= minHeight) {
            return minHeight;
        }
        double t = Math.pow(ThreadLocalRandom.current().nextDouble(), 2.4);
        return minHeight + (int) Math.round(t * (maxHeight - minHeight));
    }

    private void calve(World world, ArcticLayout.Site glacier, double cx, double cz, int count, int height) {
        double dx = cx - glacier.x();
        double dz = cz - glacier.z();
        double len = Math.hypot(dx, dz);
        double ux = len < 0.001 ? 0 : dx / len;
        double uz = len < 0.001 ? 0 : dz / len;
        Location origin = new Location(world, glacier.x() + 0.5, glacier.y() + 6, glacier.z() + 0.5);
        float volume = 1.2f + Math.min(1.4f, height * 0.12f);
        world.playSound(origin, Sound.BLOCK_GLASS_BREAK, volume, 0.45f + 0.4f / height);
        world.playSound(origin, Sound.ENTITY_GENERIC_SPLASH, volume, 0.55f);
        world.spawnParticle(Particle.CLOUD, origin, 12 + height * 4, 1.2, 0.8, 1.2, 0.02);
        world.spawnParticle(Particle.SPLASH, origin.clone().add(ux * 3, -2, uz * 3),
                16 + height * 4, 1.4, 0.4, 1.4, 0.05);

        for (int i = 0; i < count; i++) {
            double ox = (i - count / 2.0) * 0.7;
            Location spawn = origin.clone().add(-uz * ox + ux * 2, 1 + (i % 3), ux * ox + uz * 2);
            Material ice = i % 2 == 0 ? Material.PACKED_ICE : Material.BLUE_ICE;
            FallingBlock falling = world.spawnFallingBlock(spawn, ice.createBlockData());
            falling.setDropItem(false);
            falling.setHurtEntities(true);
            falling.setVelocity(new Vector(ux, 0.22, uz).multiply(0.45));
        }
    }

    private void surge(HungerGamesPlugin plugin, World world, Arena arena, ArcticLayout.Site glacier,
                       double cx, double cz, int forest, int inland, int durationTicks, int height) {
        double dx = cx - glacier.x();
        double dz = cz - glacier.z();
        double len = Math.hypot(dx, dz);
        if (len < 0.001) {
            return;
        }
        double ux = dx / len;
        double uz = dz / len;
        int minInlandDist = Math.max(20, forest - inland);
        int halfWidth = 2 + Math.min(4, (height - 1) / 2);
        List<BlockSnapshot> snapshots = new ArrayList<>();
        Set<Long> visited = new HashSet<>();

        for (int step = 2; step <= inland + 4; step++) {
            int bx = (int) Math.round(glacier.x() + ux * step);
            int bz = (int) Math.round(glacier.z() + uz * step);
            for (int w = -halfWidth; w <= halfWidth; w++) {
                int x = (int) Math.round(bx - uz * w);
                int z = (int) Math.round(bz + ux * w);
                long key = (((long) x) << 32) ^ (z & 0xffffffffL);
                if (!visited.add(key)) {
                    continue;
                }
                double colDist = CircleUtil.distanceFromCenter(cx, cz, x, z);
                if (colDist < minInlandDist) {
                    continue;
                }
                if (SpawnLocationUtil.isOnSpawnRing(x, z, cx, cz, forest)) {
                    continue;
                }
                placePulseColumn(world, arena, x, z, height, snapshots);
            }
        }

        if (snapshots.isEmpty()) {
            return;
        }
        Location splash = new Location(world, glacier.x() + ux * 6, glacier.y() + 1, glacier.z() + uz * 6);
        world.playSound(splash, Sound.ENTITY_GENERIC_SPLASH, 1.2f + height * 0.08f, 0.45f);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> restore(world, snapshots), durationTicks);
    }

    private void placePulseColumn(World world, Arena arena, int x, int z, int height,
                                  List<BlockSnapshot> snapshots) {
        int baseY = (int) arena.getCenterY();
        int groundY = VolcanoHeatUtil.findTerrainGroundY(world, x, z, baseY);
        for (int dy = 0; dy < height; dy++) {
            int y = groundY + dy;
            Block block = world.getBlockAt(x, y, z);
            Material type = block.getType();
            if (PROTECTED.contains(type) || type == Material.BEDROCK) {
                return;
            }
        }
        for (int dy = 0; dy < height; dy++) {
            int y = groundY + dy;
            Block block = world.getBlockAt(x, y, z);
            Material type = block.getType();
            if (PROTECTED.contains(type) || type == Material.LAVA || type == Material.BEDROCK) {
                continue;
            }
            snapshots.add(new BlockSnapshot(x, y, z, type));
            block.setType(Material.WATER, true);
        }
    }

    private void restore(World world, List<BlockSnapshot> snapshots) {
        if (world == null || snapshots.isEmpty()) {
            return;
        }
        for (int i = snapshots.size() - 1; i >= 0; i--) {
            BlockSnapshot snap = snapshots.get(i);
            world.getBlockAt(snap.x(), snap.y(), snap.z()).setType(snap.material(), false);
        }
    }
}
