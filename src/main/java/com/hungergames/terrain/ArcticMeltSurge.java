package com.hungergames.terrain;

import com.hungergames.HungerGamesPlugin;
import com.hungergames.arena.ArcticLayout;
import com.hungergames.arena.ArcticRing;
import com.hungergames.arena.Arena;
import com.hungergames.arena.VolcanoCaldera;
import com.hungergames.game.Game;
import com.hungergames.gamemode.GameMode;
import com.hungergames.gamemode.TerrainPhaseConfig;
import com.hungergames.util.BlockSnapshot;
import com.hungergames.util.CircleUtil;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.FallingBlock;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Glacier calving plus a traveling tsunami: water forms at the glacier,
 * the crest advances inland each tick, then the water recedes back to sea.
 */
public class ArcticMeltSurge {

    private static final Set<Material> PROTECTED = Set.of(
            Material.BEDROCK, Material.CHEST, Material.TRAPPED_CHEST,
            Material.BARREL, Material.ENDER_CHEST, Material.GOLD_BLOCK, Material.BEACON,
            Material.SPAWNER, Material.IRON_BLOCK, Material.EMERALD_BLOCK,
            Material.DIAMOND_BLOCK, Material.NETHERITE_BLOCK
    );

    private enum WavePhase {
        ADVANCE,
        HOLD,
        RECEDE
    }

    private final Set<Long> surgedGlaciers = new HashSet<>();
    private final List<TsunamiWave> activeWaves = new ArrayList<>();

    public void reset() {
        surgedGlaciers.clear();
        for (TsunamiWave wave : List.copyOf(activeWaves)) {
            wave.abortAndRestore();
        }
        activeWaves.clear();
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
        int maxConcurrent = Math.max(1, config.getInt("arcticTsunamiMaxConcurrent", 3));
        int inland = config.getInt("arcticTsunamiInlandBlocks", 0);
        int fallingIce = Math.max(0, config.getInt("arcticCalvingFallingIce", 5));
        int minHeight = Math.max(1, config.getInt("arcticTsunamiMinHeight", 2));
        int maxHeight = Math.max(minHeight, config.getInt("arcticTsunamiMaxHeight", 10));
        double cx = arena.getCenterX();
        double cz = arena.getCenterZ();

        activeWaves.removeIf(TsunamiWave::isFinished);

        for (ArcticLayout.Site glacier : arena.getArcticLayout().getGlacierSites()) {
            if (activeWaves.size() >= maxConcurrent) {
                break;
            }
            long key = (((long) glacier.x()) << 32) ^ (glacier.z() & 0xffffffffL);
            if (surgedGlaciers.contains(key)) {
                continue;
            }
            double dist = CircleUtil.distanceFromCenter(cx, cz, glacier.x(), glacier.z());
            // Launch while there is still ice between the lava front and the glacier
            // so the wave can travel inland instead of spawning on already-converted nether.
            int lead = Math.max(24, config.getInt("arcticTsunamiLeadBlocks", 100));
            if (convertedRadius < dist - lead) {
                continue;
            }
            surgedGlaciers.add(key);
            int height = rollTsunamiHeight(minHeight, maxHeight);
            int waveIce = fallingIce + Math.max(0, height - minHeight);
            calve(world, glacier, cx, cz, waveIce, height);
            startWave(plugin, world, arena, config, glacier, cx, cz, inland, height);
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

    private void startWave(HungerGamesPlugin plugin, World world, Arena arena, TerrainPhaseConfig config,
                           ArcticLayout.Site glacier, double cx, double cz, int configuredInland, int height) {
        double dx = cx - glacier.x();
        double dz = cz - glacier.z();
        double len = Math.hypot(dx, dz);
        if (len < 0.001) {
            return;
        }
        double ux = dx / len;
        double uz = dz / len;
        int reach = resolveReach(arena, glacier, configuredInland);
        int halfWidth = config.getInt("arcticTsunamiHalfWidth", 0);
        if (halfWidth <= 0) {
            halfWidth = 3 + Math.min(6, (height - 1) / 2);
        }
        List<PathColumn> path = buildPath(world, arena, glacier, ux, uz, reach, halfWidth, cx, cz);
        if (path.isEmpty()) {
            return;
        }

        int speed = Math.max(1, config.getInt("arcticTsunamiSpeedBlocksPerTick", 2));
        int recedeSpeed = Math.max(1, config.getInt("arcticTsunamiRecedeSpeedBlocksPerTick", 3));
        int waveLength = Math.max(3, config.getInt("arcticTsunamiWaveLengthBlocks", 12));
        int holdTicks = Math.max(0, config.getInt("arcticTsunamiDurationTicks", 20));
        long interval = Math.max(1, config.getInt("arcticTsunamiTickInterval", 1));
        int placeBudget = Math.max(24, config.getInt("arcticTsunamiBlocksPerTick", 180));

        TsunamiWave wave = new TsunamiWave(world, arena, path, height, speed, recedeSpeed,
                waveLength, holdTicks, placeBudget, ux, uz);
        activeWaves.add(wave);
        wave.start(plugin, interval);
    }

    private static int resolveReach(Arena arena, ArcticLayout.Site glacier, int configuredInland) {
        double dist = CircleUtil.distanceFromCenter(arena.getCenterX(), arena.getCenterZ(),
                glacier.x(), glacier.z());
        int auto = Math.max(24, (int) Math.round(dist) - VolcanoCaldera.NO_TREE_RADIUS - 8);
        if (configuredInland <= 0) {
            return auto;
        }
        return Math.min(auto, Math.max(24, configuredInland));
    }

    private static List<PathColumn> buildPath(World world, Arena arena, ArcticLayout.Site glacier,
                                              double ux, double uz, int reach, int halfWidth,
                                              double cx, double cz) {
        List<PathColumn> path = new ArrayList<>();
        Set<Long> visited = new HashSet<>();
        int arenaLimit = arena.getRadius() - 2;
        int stopRadius = VolcanoCaldera.NO_TREE_RADIUS + 6;
        for (int step = 2; step <= reach; step++) {
            int sliceStart = path.size();
            int bx = (int) Math.round(glacier.x() + ux * step);
            int bz = (int) Math.round(glacier.z() + uz * step);
            for (int w = -halfWidth; w <= halfWidth; w++) {
                int x = (int) Math.round(bx - uz * w);
                int z = (int) Math.round(bz + ux * w);
                long key = (((long) x) << 32) ^ (z & 0xffffffffL);
                if (!visited.add(key)) {
                    continue;
                }
                if (!CircleUtil.isInsideCircle(cx, cz, x, z, arenaLimit)) {
                    continue;
                }
                if (CircleUtil.distanceFromCenter(cx, cz, x, z) <= stopRadius) {
                    continue;
                }
                int groundY = VolcanoHeatUtil.findTerrainGroundY(world, x, z, (int) arena.getCenterY());
                path.add(new PathColumn(x, z, groundY, step));
            }
            if (path.size() == sliceStart) {
                // Hit caldera / arena edge; stop rather than leaving a hole and continuing.
                if (step > 8) {
                    break;
                }
            }
        }
        return path;
    }

    private static final class PathColumn {
        final int x;
        final int z;
        final int groundY;
        final int step;
        List<BlockSnapshot> snapshots;

        PathColumn(int x, int z, int groundY, int step) {
            this.x = x;
            this.z = z;
            this.groundY = groundY;
            this.step = step;
        }
    }

    private final class TsunamiWave {
        private final World world;
        private final Arena arena;
        private final List<PathColumn> path;
        private final int height;
        private final int advanceSpeed;
        private final int recedeSpeed;
        private final int waveLength;
        private final int placeBudget;
        private final double ux;
        private final double uz;
        private WavePhase phase = WavePhase.ADVANCE;
        private int holdRemaining;
        private int crestIndex;
        private int floodedFrom;
        private int floodedTo = -1;
        private BukkitTask task;
        private boolean finished;

        TsunamiWave(World world, Arena arena, List<PathColumn> path, int height,
                    int advanceSpeed, int recedeSpeed, int waveLength, int holdTicks,
                    int placeBudget, double ux, double uz) {
            this.world = world;
            this.arena = arena;
            this.path = path;
            this.height = height;
            this.advanceSpeed = advanceSpeed;
            this.recedeSpeed = recedeSpeed;
            this.waveLength = waveLength;
            this.holdRemaining = holdTicks;
            this.placeBudget = placeBudget;
            this.ux = ux;
            this.uz = uz;
        }

        void start(HungerGamesPlugin plugin, long interval) {
            task = new BukkitRunnable() {
                @Override
                public void run() {
                    tickWave();
                    if (finished) {
                        cancel();
                        task = null;
                    }
                }
            }.runTaskTimer(plugin, 0L, interval);
        }

        boolean isFinished() {
            return finished;
        }

        void abortAndRestore() {
            if (task != null) {
                task.cancel();
                task = null;
            }
            restoreRange(0, path.size());
            finished = true;
        }

        private void tickWave() {
            if (world == null || !world.isChunkLoaded(
                    (int) arena.getCenterX() >> 4, (int) arena.getCenterZ() >> 4)) {
                abortAndRestore();
                return;
            }
            switch (phase) {
                case ADVANCE -> tickAdvance();
                case HOLD -> tickHold();
                case RECEDE -> tickRecede();
                default -> throw new IllegalStateException("Unhandled tsunami phase: " + phase);
            }
        }

        private void tickAdvance() {
            int placed = 0;
            int slices = 0;
            int currentStep = -1;
            boolean sliceHadIce = false;
            boolean hitNetherFront = false;
            while (crestIndex < path.size() && placed < placeBudget) {
                PathColumn column = path.get(crestIndex);
                if (currentStep >= 0 && column.step != currentStep) {
                    if (!sliceHadIce) {
                        hitNetherFront = true;
                        break;
                    }
                    slices++;
                    if (slices >= advanceSpeed) {
                        break;
                    }
                    sliceHadIce = false;
                }
                if (column.step != currentStep) {
                    currentStep = column.step;
                    splashAt(column);
                }
                if (flood(column)) {
                    sliceHadIce = true;
                }
                placed += Math.max(1, height);
                floodedTo = crestIndex;
                crestIndex++;
            }

            int minKeepStep = (floodedTo < 0 ? 0 : path.get(floodedTo).step) - waveLength;
            while (floodedFrom <= floodedTo && path.get(floodedFrom).step < minKeepStep) {
                restoreColumn(path.get(floodedFrom));
                floodedFrom++;
            }

            if (crestIndex >= path.size() || hitNetherFront) {
                phase = holdRemaining > 0 ? WavePhase.HOLD : WavePhase.RECEDE;
            }
        }

        private void tickHold() {
            holdRemaining--;
            if (holdRemaining <= 0) {
                phase = WavePhase.RECEDE;
            }
        }

        private void tickRecede() {
            int slices = 0;
            int restored = 0;
            while (floodedTo >= floodedFrom && slices < recedeSpeed && restored < placeBudget) {
                int step = path.get(floodedTo).step;
                while (floodedTo >= floodedFrom && path.get(floodedTo).step == step) {
                    restoreColumn(path.get(floodedTo));
                    restored += Math.max(1, height);
                    floodedTo--;
                }
                slices++;
            }
            if (floodedTo < floodedFrom) {
                finished = true;
            }
        }

        private void splashAt(PathColumn column) {
            Location loc = new Location(world, column.x + 0.5, column.groundY + 1, column.z + 0.5);
            world.playSound(loc, Sound.ENTITY_GENERIC_SPLASH, 0.45f, 0.55f);
            world.spawnParticle(Particle.SPLASH, loc.add(ux, 0.2, uz), 6, 0.6, 0.3, 0.6, 0.02);
        }

        private boolean flood(PathColumn column) {
            if (column.snapshots != null) {
                return !column.snapshots.isEmpty();
            }
            Material ground = world.getBlockAt(column.x, column.groundY, column.z).getType();
            if (VolcanoNetherSpread.isNetherGround(ground) || ground == Material.LAVA) {
                column.snapshots = List.of();
                return false;
            }
            List<BlockSnapshot> snaps = new ArrayList<>(height);
            for (int dy = 0; dy < height; dy++) {
                int y = column.groundY + dy;
                Block block = world.getBlockAt(column.x, y, column.z);
                Material type = block.getType();
                if (PROTECTED.contains(type) || type == Material.LAVA || type == Material.BEDROCK) {
                    continue;
                }
                snaps.add(new BlockSnapshot(column.x, y, column.z, type));
                // Physics off: a moving wall, not a puddle that immediately spreads and settles.
                block.setType(Material.WATER, false);
            }
            column.snapshots = snaps;
            return !snaps.isEmpty();
        }

        private void restoreRange(int fromInclusive, int toExclusive) {
            int from = Math.max(0, fromInclusive);
            int to = Math.min(path.size(), toExclusive);
            for (int i = from; i < to; i++) {
                restoreColumn(path.get(i));
            }
        }

        private void restoreColumn(PathColumn column) {
            if (column.snapshots == null || column.snapshots.isEmpty()) {
                column.snapshots = null;
                return;
            }
            for (int i = column.snapshots.size() - 1; i >= 0; i--) {
                BlockSnapshot snap = column.snapshots.get(i);
                Block block = world.getBlockAt(snap.x(), snap.y(), snap.z());
                Material current = block.getType();
                if (PROTECTED.contains(current) || current == Material.LAVA) {
                    continue;
                }
                block.setType(snap.material(), false);
            }
            column.snapshots = null;
        }
    }
}
