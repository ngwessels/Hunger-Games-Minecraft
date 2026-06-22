package com.hungergames.terrain.phases;

import com.hungergames.HungerGamesPlugin;
import com.hungergames.arena.Arena;
import com.hungergames.game.Game;
import com.hungergames.gamemode.TerrainPhaseConfig;
import com.hungergames.terrain.TerrainPhase;
import com.hungergames.util.BlockBatchUtil;
import com.hungergames.util.CircleUtil;
import com.hungergames.util.NoiseUtil;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;

import java.util.ArrayList;
import java.util.List;

public class NetherTransformPhase implements TerrainPhase {

    private final HungerGamesPlugin plugin;
    private long endTime;
    private int currentX;
    private int minX;
    private int maxX;
    private int minZ;
    private int maxZ;
    private double cx;
    private double cz;
    private int radius;
    private int blocksPerTick;

    public NetherTransformPhase(HungerGamesPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "NETHER_TRANSFORM";
    }

    @Override
    public void onStart(Game game, Arena arena, TerrainPhaseConfig config) {
        int resolved = plugin.getTerrainPhaseManager().getResolvedDurationSeconds();
        endTime = System.currentTimeMillis() + resolved * 1000L;
        cx = arena.getCenterX();
        cz = arena.getCenterZ();
        radius = arena.getRadius();
        minX = (int) (cx - radius);
        maxX = (int) (cx + radius);
        minZ = (int) (cz - radius);
        maxZ = (int) (cz + radius);
        currentX = minX;

        String speed = config.getString("transformSpeed", "medium");
        blocksPerTick = switch (speed.toLowerCase()) {
            case "fast" -> plugin.getPluginConfig().getBlocksPerTick() * 2;
            case "slow" -> plugin.getPluginConfig().getBlocksPerTick() / 2;
            default -> plugin.getPluginConfig().getBlocksPerTick();
        };
    }

    @Override
    public void onTick(Game game, Arena arena, TerrainPhaseConfig config) {
        World world = arena.getWorld();
        if (world == null) {
            return;
        }

        List<BlockBatchUtil.BlockChange> changes = new ArrayList<>();
        int processed = 0;
        while (currentX <= maxX && processed < blocksPerTick) {
            for (int z = minZ; z <= maxZ && processed < blocksPerTick; z++) {
                if (!CircleUtil.isInsideCircle(cx, cz, currentX, z, radius - 2)) {
                    continue;
                }
                for (int y = 1; y <= arena.getWallHeight(); y++) {
                    Block block = world.getBlockAt(currentX, y, z);
                    Material replacement = transform(block.getType(), currentX, y, z);
                    if (replacement != null && replacement != block.getType()) {
                        changes.add(new BlockBatchUtil.BlockChange(currentX, y, z, replacement));
                        processed++;
                        if (processed >= blocksPerTick) {
                            break;
                        }
                    }
                }
                world.setBiome(currentX, (int) arena.getCenterY(), z, Biome.NETHER_WASTES);
            }
            currentX++;
        }

        plugin.getBlockMutationQueue().enqueueAll(changes);
    }

    private Material transform(Material current, int x, int y, int z) {
        return switch (current) {
            case GRASS_BLOCK, PODZOL, DIRT, COARSE_DIRT -> Material.NETHERRACK;
            case STONE, DEEPSLATE, COBBLESTONE -> Material.NETHERRACK;
            case OAK_LOG, OAK_LEAVES, BIRCH_LOG, BIRCH_LEAVES -> Material.NETHER_WART_BLOCK;
            case WATER -> Material.LAVA;
            case AIR -> {
                if (NoiseUtil.noise2D(x, z, y) > 0.92) {
                    yield Material.GLOWSTONE;
                } else if (NoiseUtil.noise2D(x, z, y + 7) > 0.97) {
                    yield Material.LAVA;
                }
                yield null;
            }
            default -> null;
        };
    }

    @Override
    public boolean isComplete() {
        return System.currentTimeMillis() >= endTime && currentX > maxX
                && plugin.getBlockMutationQueue().isIdle();
    }

    @Override
    public void onEnd(Game game, Arena arena, TerrainPhaseConfig config) {
    }
}
