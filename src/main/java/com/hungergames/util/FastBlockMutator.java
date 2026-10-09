package com.hungergames.util;

import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * High-throughput block writes for the comet shockwave. When NMS is available
 * (Paper / CraftBukkit), edits paletted chunk sections directly so neighbor
 * physics and Bukkit wrapper allocation are skipped. Lighting and client
 * block updates are still applied so the crater looks the same. Falls back to
 * chunk-local {@code setBlockData(..., false)} if reflection fails.
 */
public final class FastBlockMutator {

    private static final Object INIT_LOCK = new Object();

    private static volatile boolean initialized;
    private static volatile boolean nmsAvailable;

    private static Method worldGetHandle;
    private static Method chunkGetHandle;
    private static Method getSectionIndex;
    private static Method getSection;
    private static Method sectionGetBlockState;
    private static Method sectionSetBlockState;
    private static Method stateIsAir;
    private static Method stateHasBlockEntity;
    private static Method stateGetBukkitMaterial;
    private static Method chunkMarkUnsaved;
    private static Method getChunkSource;
    private static Method getLightEngine;
    private static Method lightCheckBlock;
    private static Method sendBlockUpdated;
    private static Method craftDataGetState;
    private static Method heightmapUpdate;
    private static Field heightmapsField;
    private static Constructor<?> blockPosCtor;

    private static Object nmsAir;
    private static Object nmsBedrock;
    private static final Map<BlockData, Object> NMS_STATE_CACHE = new IdentityHashMap<>();

    private World world;
    private Object lastNmsWorld;
    private Chunk lastChunk;
    private int lastChunkX = Integer.MIN_VALUE;
    private int lastChunkZ = Integer.MIN_VALUE;
    private Object lastNmsChunk;
    private Object lastLightEngine;
    private boolean lastNmsChunkDirty;

    public void beginTick(World world) {
        this.world = world;
        lastChunk = null;
        lastChunkX = Integer.MIN_VALUE;
        lastChunkZ = Integer.MIN_VALUE;
        lastNmsChunk = null;
        lastLightEngine = null;
        lastNmsChunkDirty = false;
        lastNmsWorld = null;
        ensureInitialized(world);
        if (nmsAvailable && worldGetHandle != null) {
            try {
                lastNmsWorld = worldGetHandle.invoke(world);
            } catch (ReflectiveOperationException | RuntimeException ex) {
                nmsAvailable = false;
            }
        }
    }

    public void endTick() {
        markLastChunkUnsaved();
        lastChunk = null;
        lastNmsChunk = null;
        lastLightEngine = null;
        lastNmsWorld = null;
        world = null;
    }

    public Chunk chunkAt(int blockX, int blockZ) {
        if (world == null) {
            return null;
        }
        int chunkX = blockX >> 4;
        int chunkZ = blockZ >> 4;
        if (lastChunk != null && chunkX == lastChunkX && chunkZ == lastChunkZ) {
            return lastChunk;
        }
        markLastChunkUnsaved();
        lastChunkX = chunkX;
        lastChunkZ = chunkZ;
        lastChunk = world.getChunkAt(chunkX, chunkZ);
        lastNmsChunk = nmsHandle(lastChunk);
        lastNmsChunkDirty = false;
        lastLightEngine = null;
        return lastChunk;
    }

    public int highestBlockY(int blockX, int blockZ) {
        return world.getHighestBlockYAt(blockX, blockZ);
    }

    public void set(Chunk chunk, int x, int y, int z, BlockData data) {
        if (world == null || chunk == null || data == null) {
            return;
        }
        if (y < world.getMinHeight() || y >= world.getMaxHeight()) {
            return;
        }
        int lx = x & 15;
        int lz = z & 15;
        if (nmsAvailable && lastNmsChunk != null && tryNmsSet(chunk, lx, y, lz, x, z, data)) {
            return;
        }
        Block block = chunk.getBlock(lx, y, lz);
        Material current = block.getType();
        if (current == data.getMaterial() || current == Material.BEDROCK) {
            return;
        }
        block.setBlockData(data, false);
    }

    public boolean isSkippable(Chunk chunk, int x, int y, int z) {
        if (world == null || chunk == null) {
            return true;
        }
        if (y < world.getMinHeight() || y >= world.getMaxHeight()) {
            return true;
        }
        int lx = x & 15;
        int lz = z & 15;
        if (nmsAvailable && lastNmsChunk != null) {
            Material type = nmsType(lx, y, lz);
            if (type != null) {
                return type.isAir() || type == Material.BEDROCK;
            }
        }
        Material type = chunk.getBlock(lx, y, lz).getType();
        return type.isAir() || type == Material.BEDROCK;
    }

    public boolean isAir(Chunk chunk, int x, int y, int z) {
        if (world == null || chunk == null) {
            return true;
        }
        if (y < world.getMinHeight() || y >= world.getMaxHeight()) {
            return true;
        }
        int lx = x & 15;
        int lz = z & 15;
        if (nmsAvailable && lastNmsChunk != null) {
            Material type = nmsType(lx, y, lz);
            if (type != null) {
                return type.isAir();
            }
        }
        return chunk.getBlock(lx, y, lz).getType().isAir();
    }

    private boolean tryNmsSet(Chunk chunk, int lx, int y, int lz, int x, int z, BlockData data) {
        try {
            Object nmsState = nmsState(data);
            if (nmsState == null) {
                return false;
            }
            Object section = sectionAt(y);
            if (section == null) {
                return false;
            }
            Object oldState = sectionGetBlockState.invoke(section, lx, y & 15, lz);
            if (oldState == nmsState || (isAir(oldState) && data.getMaterial() == Material.AIR)) {
                return true;
            }
            if (isBedrock(oldState)) {
                return true;
            }
            if (hasBlockEntity(oldState)) {
                Block block = chunk.getBlock(lx, y, lz);
                if (block.getType() != data.getMaterial()) {
                    block.setBlockData(data, false);
                }
                return true;
            }
            sectionSetBlockState.invoke(section, lx, y & 15, lz, nmsState);
            lastNmsChunkDirty = true;
            updateHeightmaps(lx, y, lz, nmsState);
            Object pos = blockPosCtor.newInstance(x, y, z);
            queueLight(pos);
            sendBlockUpdated.invoke(lastNmsWorld, pos, oldState, nmsState, 2);
            return true;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            nmsAvailable = false;
            return false;
        }
    }

    private Material nmsType(int lx, int y, int lz) {
        try {
            Object section = sectionAt(y);
            if (section == null) {
                return null;
            }
            Object state = sectionGetBlockState.invoke(section, lx, y & 15, lz);
            if (isAir(state)) {
                return Material.AIR;
            }
            if (isBedrock(state)) {
                return Material.BEDROCK;
            }
            if (stateGetBukkitMaterial != null) {
                Object material = stateGetBukkitMaterial.invoke(state);
                return material instanceof Material ? (Material) material : null;
            }
            return null;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return null;
        }
    }

    private Object sectionAt(int y) throws ReflectiveOperationException {
        int index = (Integer) getSectionIndex.invoke(lastNmsChunk, y);
        return getSection.invoke(lastNmsChunk, index);
    }

    private void updateHeightmaps(int lx, int y, int lz, Object nmsState) throws ReflectiveOperationException {
        if (heightmapsField == null || heightmapUpdate == null || lastNmsChunk == null) {
            return;
        }
        Object maps = heightmapsField.get(lastNmsChunk);
        if (!(maps instanceof Map<?, ?> heightmaps)) {
            return;
        }
        for (Object heightmap : heightmaps.values()) {
            heightmapUpdate.invoke(heightmap, lx, y, lz, nmsState);
        }
    }

    private void queueLight(Object pos) throws ReflectiveOperationException {
        if (lightCheckBlock == null || lastNmsWorld == null) {
            return;
        }
        if (lastLightEngine == null) {
            Object chunkSource = getChunkSource.invoke(lastNmsWorld);
            lastLightEngine = getLightEngine.invoke(chunkSource);
        }
        if (lastLightEngine != null) {
            lightCheckBlock.invoke(lastLightEngine, pos);
        }
    }

    private void markLastChunkUnsaved() {
        if (!lastNmsChunkDirty || lastNmsChunk == null || chunkMarkUnsaved == null) {
            lastNmsChunkDirty = false;
            return;
        }
        try {
            chunkMarkUnsaved.invoke(lastNmsChunk);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            nmsAvailable = false;
        }
        lastNmsChunkDirty = false;
    }

    private static Object nmsState(BlockData data) throws ReflectiveOperationException {
        if (craftDataGetState == null || data == null) {
            return null;
        }
        Object cached = NMS_STATE_CACHE.get(data);
        if (cached != null) {
            return cached;
        }
        Object state = craftDataGetState.invoke(data);
        if (state != null) {
            NMS_STATE_CACHE.put(data, state);
        }
        return state;
    }

    private static boolean isAir(Object nmsState) throws ReflectiveOperationException {
        if (nmsState == null) {
            return true;
        }
        if (nmsState == nmsAir) {
            return true;
        }
        return stateIsAir != null && Boolean.TRUE.equals(stateIsAir.invoke(nmsState));
    }

    private static boolean isBedrock(Object nmsState) {
        return nmsState != null && nmsState == nmsBedrock;
    }

    private static boolean hasBlockEntity(Object nmsState) throws ReflectiveOperationException {
        return stateHasBlockEntity != null && Boolean.TRUE.equals(stateHasBlockEntity.invoke(nmsState));
    }

    private static Object nmsHandle(Chunk chunk) {
        if (!nmsAvailable || chunkGetHandle == null) {
            return null;
        }
        try {
            return chunkGetHandle.invoke(chunk);
        } catch (ReflectiveOperationException | RuntimeException ex) {
            nmsAvailable = false;
            return null;
        }
    }

    private static void ensureInitialized(World world) {
        if (initialized) {
            return;
        }
        synchronized (INIT_LOCK) {
            if (initialized) {
                return;
            }
            initialized = true;
            try {
                worldGetHandle = world.getClass().getMethod("getHandle");
                Chunk probe = world.getChunkAt(world.getSpawnLocation());
                chunkGetHandle = probe.getClass().getMethod("getHandle");
                Object nmsChunk = chunkGetHandle.invoke(probe);

                getSectionIndex = findMethod(nmsChunk.getClass(), "getSectionIndex", int.class);
                getSection = findMethod(nmsChunk.getClass(), "getSection", int.class);
                if (getSectionIndex == null || getSection == null) {
                    return;
                }
                Object section = getSection.invoke(nmsChunk, getSectionIndex.invoke(nmsChunk, world.getMinHeight()));
                sectionGetBlockState = findMethod(section.getClass(), "getBlockState", int.class, int.class, int.class);
                sectionSetBlockState = findNamed(section.getClass(), "setBlockState", 4);
                if (sectionGetBlockState == null || sectionSetBlockState == null) {
                    return;
                }

                BlockData airData = Material.AIR.createBlockData();
                craftDataGetState = findMethod(airData.getClass(), "getState");
                if (craftDataGetState == null) {
                    return;
                }
                nmsAir = craftDataGetState.invoke(airData);
                nmsBedrock = craftDataGetState.invoke(Material.BEDROCK.createBlockData());
                if (nmsAir != null) {
                    stateIsAir = findMethod(nmsAir.getClass(), "isAir");
                    stateHasBlockEntity = findMethod(nmsAir.getClass(), "hasBlockEntity");
                    stateGetBukkitMaterial = findMethod(nmsAir.getClass(), "getBukkitMaterial");
                }

                Class<?> blockPosClass = Class.forName("net.minecraft.core.BlockPos");
                blockPosCtor = blockPosClass.getConstructor(int.class, int.class, int.class);
                chunkMarkUnsaved = findMethod(nmsChunk.getClass(), "markUnsaved");
                heightmapsField = findField(nmsChunk.getClass(), "heightmaps");
                if (heightmapsField != null) {
                    Object maps = heightmapsField.get(nmsChunk);
                    if (maps instanceof Map<?, ?> heightmaps && !heightmaps.isEmpty()) {
                        Object sample = heightmaps.values().iterator().next();
                        heightmapUpdate = findNamed(sample.getClass(), "update", 4);
                    }
                }

                Object nmsWorld = worldGetHandle.invoke(world);
                getChunkSource = findMethod(nmsWorld.getClass(), "getChunkSource");
                if (getChunkSource != null) {
                    Object chunkSource = getChunkSource.invoke(nmsWorld);
                    getLightEngine = findMethod(chunkSource.getClass(), "getLightEngine");
                    if (getLightEngine != null) {
                        Object lightEngine = getLightEngine.invoke(chunkSource);
                        lightCheckBlock = findNamed(lightEngine.getClass(), "checkBlock", 1);
                    }
                }
                sendBlockUpdated = findNamed(nmsWorld.getClass(), "sendBlockUpdated", 4);

                nmsAvailable = lightCheckBlock != null
                        && sendBlockUpdated != null
                        && blockPosCtor != null
                        && lastHandleReady();
            } catch (ReflectiveOperationException | RuntimeException ex) {
                nmsAvailable = false;
            }
        }
    }

    private static boolean lastHandleReady() {
        return nmsAir != null && sectionSetBlockState != null && sectionGetBlockState != null;
    }

    private static Method findNamed(Class<?> type, String name, int parameterCount) {
        Class<?> current = type;
        while (current != null) {
            for (Method method : current.getDeclaredMethods()) {
                if (name.equals(method.getName()) && method.getParameterCount() == parameterCount) {
                    method.setAccessible(true);
                    return method;
                }
            }
            current = current.getSuperclass();
        }
        for (Method method : type.getMethods()) {
            if (name.equals(method.getName()) && method.getParameterCount() == parameterCount) {
                return method;
            }
        }
        return null;
    }

    private static Field findField(Class<?> type, String name) {
        Class<?> current = type;
        while (current != null) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ex) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    private static Method findMethod(Class<?> type, String name, Class<?>... params) {
        Class<?> current = type;
        while (current != null) {
            try {
                Method method = current.getDeclaredMethod(name, params);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ex) {
                current = current.getSuperclass();
            }
        }
        try {
            return type.getMethod(name, params);
        } catch (NoSuchMethodException | RuntimeException ex) {
            return null;
        }
    }
}
