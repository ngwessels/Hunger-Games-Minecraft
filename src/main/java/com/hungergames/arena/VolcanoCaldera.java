package com.hungergames.arena;

import com.hungergames.util.BlockBatchUtil;
import com.hungergames.util.CircleUtil;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Levelled;

import java.util.List;

public final class VolcanoCaldera {

    public static final int CALDERA_LAVA_RADIUS = 5;
    public static final int RIM_RADIUS = 7;
    public static final int NO_TREE_RADIUS = 10;

    private VolcanoCaldera() {
    }

    public static void addCaldera(List<BlockBatchUtil.BlockChange> changes, double cx, int surfaceY, double cz) {
        int icx = (int) cx;
        int icz = (int) cz;

        for (int dx = -RIM_RADIUS; dx <= RIM_RADIUS; dx++) {
            for (int dz = -RIM_RADIUS; dz <= RIM_RADIUS; dz++) {
                int x = icx + dx;
                int z = icz + dz;
                double dist = Math.sqrt(dx * dx + dz * dz);

                if (dist <= CALDERA_LAVA_RADIUS) {
                    changes.add(new BlockBatchUtil.BlockChange(x, surfaceY, z, Material.AIR));
                    changes.add(new BlockBatchUtil.BlockChange(x, surfaceY - 1, z, Material.LAVA, createLavaSourceData()));
                    changes.add(new BlockBatchUtil.BlockChange(x, surfaceY - 2, z, Material.BLACKSTONE));
                } else if (dist <= RIM_RADIUS) {
                    changes.add(new BlockBatchUtil.BlockChange(x, surfaceY, z, Material.BLACKSTONE));
                    if (dist > CALDERA_LAVA_RADIUS + 0.5 && dist < RIM_RADIUS - 0.5) {
                        changes.add(new BlockBatchUtil.BlockChange(x, surfaceY - 1, z, Material.MAGMA_BLOCK));
                    }
                }
            }
        }
    }

    /** Re-applies caldera lava with physics after batched arena placement. */
    public static void activateCalderaLava(World world, double cx, int surfaceY, double cz) {
        int icx = (int) cx;
        int icz = (int) cz;
        int lavaY = surfaceY - 1;

        for (int dx = -CALDERA_LAVA_RADIUS; dx <= CALDERA_LAVA_RADIUS; dx++) {
            for (int dz = -CALDERA_LAVA_RADIUS; dz <= CALDERA_LAVA_RADIUS; dz++) {
                if (dx * dx + dz * dz > CALDERA_LAVA_RADIUS * CALDERA_LAVA_RADIUS) {
                    continue;
                }
                placeLavaSource(world.getBlockAt(icx + dx, lavaY, icz + dz));
            }
        }
    }

    public static BlockData createLavaSourceData() {
        BlockData data = Material.LAVA.createBlockData();
        if (data instanceof Levelled levelled) {
            levelled.setLevel(0);
        }
        return data;
    }

    public static boolean isLavaSource(Block block) {
        if (block.getType() != Material.LAVA) {
            return false;
        }
        BlockData data = block.getBlockData();
        if (data instanceof Levelled levelled) {
            return levelled.getLevel() == 0;
        }
        return true;
    }

    public static void placeLavaSource(Block block) {
        BlockData data = createLavaSourceData();
        block.setBlockData(data, true);
    }

    public static boolean isInsideCalderaArea(double cx, double cz, int x, int z) {
        return CircleUtil.isInsideCircle(cx, cz, x, z, NO_TREE_RADIUS);
    }
}
