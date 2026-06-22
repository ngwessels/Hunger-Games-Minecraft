package com.hungergames.util;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public final class BlockBatchUtil {

    private BlockBatchUtil() {
    }

    public static class BlockChange {
        public final int x;
        public final int y;
        public final int z;
        public final Material material;
        public final BlockData blockData;

        public BlockChange(int x, int y, int z, Material material) {
            this(x, y, z, material, null);
        }

        public BlockChange(int x, int y, int z, Material material, BlockData blockData) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.material = material;
            this.blockData = blockData;
        }
    }

    public static void applyBatched(JavaPlugin plugin, World world, List<BlockChange> changes,
                                    int blocksPerTick, Consumer<Double> progressCallback, Runnable onComplete) {
        if (changes.isEmpty()) {
            if (onComplete != null) {
                onComplete.run();
            }
            return;
        }

        new BukkitRunnable() {
            private int index = 0;

            @Override
            public void run() {
                int end = Math.min(index + blocksPerTick, changes.size());
                for (int i = index; i < end; i++) {
                    BlockChange change = changes.get(i);
                    Block block = world.getBlockAt(change.x, change.y, change.z);
                    block.setType(change.material, false);
                    if (change.blockData != null) {
                        block.setBlockData(change.blockData, false);
                    }
                }
                index = end;
                if (progressCallback != null) {
                    progressCallback.accept((double) index / changes.size());
                }
                if (index >= changes.size()) {
                    cancel();
                    if (onComplete != null) {
                        onComplete.run();
                    }
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    public static List<BlockChange> newChangeList() {
        return new ArrayList<>();
    }
}
