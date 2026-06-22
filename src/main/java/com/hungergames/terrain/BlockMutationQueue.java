package com.hungergames.terrain;

import com.hungergames.HungerGamesPlugin;
import com.hungergames.util.BlockBatchUtil;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class BlockMutationQueue {

    private final HungerGamesPlugin plugin;
    private final List<BlockBatchUtil.BlockChange> pending = new ArrayList<>();
    private BukkitRunnable task;
    private Consumer<Double> progressListener;

    public BlockMutationQueue(HungerGamesPlugin plugin) {
        this.plugin = plugin;
    }

    public synchronized void enqueue(int x, int y, int z, Material material) {
        pending.add(new BlockBatchUtil.BlockChange(x, y, z, material));
        startIfNeeded();
    }

    public synchronized void enqueueAll(List<BlockBatchUtil.BlockChange> changes) {
        pending.addAll(changes);
        startIfNeeded();
    }

    public void setProgressListener(Consumer<Double> progressListener) {
        this.progressListener = progressListener;
    }

    public synchronized boolean isIdle() {
        return pending.isEmpty() && task == null;
    }

    public synchronized int pendingCount() {
        return pending.size();
    }

    public synchronized void clear() {
        pending.clear();
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private synchronized void startIfNeeded() {
        if (task != null || pending.isEmpty()) {
            return;
        }
        task = new BukkitRunnable() {
            @Override
            public void run() {
                processBatch();
            }
        };
        task.runTaskTimer(plugin, 0L, 1L);
    }

    private synchronized void processBatch() {
        if (pending.isEmpty()) {
            if (task != null) {
                task.cancel();
                task = null;
            }
            return;
        }

        int batchSize = plugin.getPluginConfig().getBlocksPerTick();
        int count = Math.min(batchSize, pending.size());
        List<BlockBatchUtil.BlockChange> batch = new ArrayList<>(pending.subList(0, count));
        pending.subList(0, count).clear();

        World world = plugin.getGameManager().getActiveGame()
                .map(game -> game.getArena().getWorld())
                .orElse(null);
        if (world == null) {
            pending.clear();
            if (task != null) {
                task.cancel();
                task = null;
            }
            return;
        }

        for (BlockBatchUtil.BlockChange change : batch) {
            world.getBlockAt(change.x, change.y, change.z).setType(change.material, false);
        }

        if (progressListener != null) {
            progressListener.accept(1.0 - ((double) pending.size() / Math.max(1, pending.size() + count)));
        }

        if (pending.isEmpty() && task != null) {
            task.cancel();
            task = null;
        }
    }
}
