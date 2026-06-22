package com.hungergames.util;

import com.hungergames.arena.Arena;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

public final class ArenaEntityUtil {

    public static final NamespacedKey PHASE_MOB_KEY = new NamespacedKey("hungergames", "phase_mob");
    public static final NamespacedKey VOLCANO_MOB_KEY = PHASE_MOB_KEY;
    public static final NamespacedKey VOLCANO_TNT_KEY = new NamespacedKey("hungergames", "volcano_tnt");
    public static final NamespacedKey MAGMA_TNT_VISUAL_KEY = new NamespacedKey("hungergames", "magma_tnt_visual");
    public static final NamespacedKey FIRE_TORNADO_KEY = new NamespacedKey("hungergames", "fire_tornado");
    public static final NamespacedKey VOLCANO_GUARDIAN_KEY = new NamespacedKey("hungergames", "volcano_guardian");

    public static final NamespacedKey POWER_UP_KEY = new NamespacedKey("hungergames", "power_up");
    public static final NamespacedKey PLAYER_PILOT_GUARDIAN_KEY = new NamespacedKey("hungergames", "player_pilot_guardian");

    private ArenaEntityUtil() {
    }

    public static void tagPhaseMob(Plugin plugin, LivingEntity entity) {
        entity.getPersistentDataContainer().set(PHASE_MOB_KEY, PersistentDataType.BYTE, (byte) 1);
        entity.setRemoveWhenFarAway(true);
    }

    public static void tagVolcanoMob(Plugin plugin, LivingEntity entity) {
        tagPhaseMob(plugin, entity);
    }

    public static void tagVolcanoGuardian(Plugin plugin, LivingEntity entity) {
        entity.getPersistentDataContainer().set(VOLCANO_GUARDIAN_KEY, PersistentDataType.BYTE, (byte) 1);
        entity.getPersistentDataContainer().set(PHASE_MOB_KEY, PersistentDataType.BYTE, (byte) 1);
        entity.setRemoveWhenFarAway(false);
    }

    public static boolean isVolcanoGuardian(LivingEntity living) {
        return living.getPersistentDataContainer().has(VOLCANO_GUARDIAN_KEY, PersistentDataType.BYTE);
    }

    public static void tagVolcanoTnt(Plugin plugin, TNTPrimed tnt) {
        tnt.getPersistentDataContainer().set(VOLCANO_TNT_KEY, PersistentDataType.BYTE, (byte) 1);
    }

    public static boolean isVolcanoTnt(TNTPrimed tnt) {
        return tnt.getPersistentDataContainer().has(VOLCANO_TNT_KEY, PersistentDataType.BYTE);
    }

    public static boolean isMagmaTntVisual(Entity entity) {
        return entity.getPersistentDataContainer().has(MAGMA_TNT_VISUAL_KEY, PersistentDataType.BYTE);
    }

    public static boolean isPowerUpMarker(org.bukkit.entity.Entity entity) {
        return entity instanceof ArmorStand stand
                && stand.getPersistentDataContainer().has(POWER_UP_KEY, PersistentDataType.STRING);
    }

    public static void tagPlayerPilotGuardian(Plugin plugin, LivingEntity entity) {
        entity.getPersistentDataContainer().set(PLAYER_PILOT_GUARDIAN_KEY, PersistentDataType.BYTE, (byte) 1);
        entity.setRemoveWhenFarAway(false);
    }

    public static boolean isPlayerPilotGuardian(LivingEntity living) {
        return living.getPersistentDataContainer().has(PLAYER_PILOT_GUARDIAN_KEY, PersistentDataType.BYTE);
    }

    public static void clearPhaseEntities(World world, Arena arena) {
        clearVolcanoEntities(world, arena);
    }

    public static void clearVolcanoEntities(World world, Arena arena) {
        if (world == null) {
            return;
        }
        double cx = arena.getCenterX();
        double cz = arena.getCenterZ();
        double maxDist = arena.getRadius() + 5;

        for (Entity entity : world.getEntities()) {
            if (!isNearArena(entity.getLocation(), cx, cz, maxDist)) {
                continue;
            }
            if (entity instanceof TNTPrimed || entity instanceof FallingBlock) {
                entity.remove();
                continue;
            }
            if (isMagmaTntVisual(entity)) {
                entity.remove();
                continue;
            }
            if (entity instanceof ArmorStand stand
                    && stand.getPersistentDataContainer().has(FIRE_TORNADO_KEY, PersistentDataType.BYTE)) {
                entity.remove();
                continue;
            }
            if (isPowerUpMarker(entity)) {
                entity.remove();
                continue;
            }
            if (entity instanceof LivingEntity living && isPlayerPilotGuardian(living)) {
                entity.remove();
                continue;
            }
            if (entity instanceof LivingEntity living && isPhaseMob(living)) {
                entity.remove();
            }
        }
    }

    public static boolean isPhaseMob(LivingEntity living) {
        return living.getPersistentDataContainer().has(PHASE_MOB_KEY, PersistentDataType.BYTE);
    }

    public static int countPhaseMobs(World world, Arena arena) {
        return countPhaseMobsOfType(world, arena, null);
    }

    public static int countVolcanoMobs(World world, Arena arena) {
        return countPhaseMobs(world, arena);
    }

    public static int countPhaseMobsOfType(World world, Arena arena, EntityType type) {
        if (world == null) {
            return 0;
        }
        double cx = arena.getCenterX();
        double cz = arena.getCenterZ();
        double maxDist = arena.getRadius() + 5;
        int count = 0;
        for (Entity entity : world.getEntities()) {
            if (!(entity instanceof LivingEntity living)) {
                continue;
            }
            if (type != null && living.getType() != type) {
                continue;
            }
            if (isPhaseMob(living) && isNearArena(entity.getLocation(), cx, cz, maxDist)) {
                count++;
            }
        }
        return count;
    }

    public static int countVolcanoMobsOfType(World world, Arena arena, EntityType type) {
        return countPhaseMobsOfType(world, arena, type);
    }

    private static boolean isNearArena(org.bukkit.Location loc, double cx, double cz, double maxDist) {
        double dx = loc.getX() - cx;
        double dz = loc.getZ() - cz;
        return dx * dx + dz * dz <= maxDist * maxDist;
    }
}
