package com.hungergames.util;

import com.hungergames.arena.Arena;
import org.bukkit.GameRule;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

public final class ArenaEntityUtil {

    public static final NamespacedKey PHASE_MOB_KEY = new NamespacedKey("hungergames", "phase_mob");
    public static final NamespacedKey VOLCANO_MOB_KEY = PHASE_MOB_KEY;
    public static final NamespacedKey VOLCANO_TNT_KEY = new NamespacedKey("hungergames", "volcano_tnt");
    public static final NamespacedKey MAGMA_TNT_VISUAL_KEY = new NamespacedKey("hungergames", "magma_tnt_visual");
    public static final NamespacedKey FIRE_TORNADO_KEY = new NamespacedKey("hungergames", "fire_tornado");
    public static final NamespacedKey VOLCANO_GUARDIAN_KEY = new NamespacedKey("hungergames", "volcano_guardian");
    public static final NamespacedKey VOLCANO_PIGLIN_KEY = new NamespacedKey("hungergames", "volcano_piglin");
    public static final NamespacedKey VOLCANO_PIGLIN_BUFFED_KEY = new NamespacedKey("hungergames", "volcano_piglin_buffed");
    public static final NamespacedKey VOLCANO_PIGLIN_HUNT_OWNER_KEY =
            new NamespacedKey("hungergames", "volcano_piglin_hunt_owner");
    public static final NamespacedKey VOLCANO_CREEPER_KEY = new NamespacedKey("hungergames", "volcano_creeper");
    public static final NamespacedKey RAGE_CREEPER_KEY = new NamespacedKey("hungergames", "rage_creeper");
    public static final NamespacedKey RAGE_GOLEM_KEY = new NamespacedKey("hungergames", "rage_golem");
    public static final NamespacedKey RAGE_UNIT_BUFFED_KEY = new NamespacedKey("hungergames", "rage_unit_buffed");

    public static final NamespacedKey POWER_UP_KEY = new NamespacedKey("hungergames", "power_up");
    public static final NamespacedKey PLAYER_PILOT_GUARDIAN_KEY = new NamespacedKey("hungergames", "player_pilot_guardian");
    public static final NamespacedKey SETTLEMENT_MOB_KEY = new NamespacedKey("hungergames", "settlement_mob");
    public static final NamespacedKey ARCTIC_MOB_KEY = new NamespacedKey("hungergames", "arctic_mob");
    public static final NamespacedKey FACTION_ICE_KEY = new NamespacedKey("hungergames", "faction_ice");
    public static final NamespacedKey FACTION_VOLCANO_KEY = new NamespacedKey("hungergames", "faction_volcano");
    public static final NamespacedKey COMET_KEY = new NamespacedKey("hungergames", "volcano_comet");
    public static final NamespacedKey BEAST_FORM_KEY = new NamespacedKey("hungergames", "beast_form");
    public static final NamespacedKey BEAST_CAMERA_KEY = new NamespacedKey("hungergames", "beast_camera");
    public static final NamespacedKey CHURCH_PROJECTILE_KEY = new NamespacedKey("hungergames", "church_projectile");
    private static final NamespacedKey SIMULATION_TARGET_KEY =
            new NamespacedKey("hungergames", "simulation_test_target");

    private static final Set<CreatureSpawnEvent.SpawnReason> VANILLA_HOSTILE_SPAWN_REASONS = EnumSet.of(
            CreatureSpawnEvent.SpawnReason.NATURAL,
            CreatureSpawnEvent.SpawnReason.CHUNK_GEN,
            CreatureSpawnEvent.SpawnReason.DEFAULT,
            CreatureSpawnEvent.SpawnReason.REINFORCEMENTS,
            CreatureSpawnEvent.SpawnReason.PATROL,
            CreatureSpawnEvent.SpawnReason.RAID,
            CreatureSpawnEvent.SpawnReason.TRIAL_SPAWNER,
            CreatureSpawnEvent.SpawnReason.NETHER_PORTAL,
            CreatureSpawnEvent.SpawnReason.VILLAGE_INVASION,
            CreatureSpawnEvent.SpawnReason.SPAWNER,
            CreatureSpawnEvent.SpawnReason.SILVERFISH_BLOCK,
            CreatureSpawnEvent.SpawnReason.JOCKEY,
            CreatureSpawnEvent.SpawnReason.MOUNT
    );

    private ArenaEntityUtil() {
    }

    public static boolean isHostileMob(Entity entity) {
        return entity instanceof Enemy && !(entity instanceof Player);
    }

    public static boolean isVanillaHostileSpawn(CreatureSpawnEvent event) {
        return isHostileMob(event.getEntity())
                && VANILLA_HOSTILE_SPAWN_REASONS.contains(event.getSpawnReason());
    }

    /**
     * Arena worlds only spawn hostiles through plugin systems. Vanilla biome
     * cycles (basalt deltas magma cubes, night creepers, pillager patrols) are off.
     */
    public static void suppressVanillaMonsterSpawning(World world) {
        if (world == null) {
            return;
        }
        world.setSpawnFlags(false, true);
        world.setGameRule(GameRule.DO_PATROL_SPAWNING, false);
        world.setGameRule(GameRule.DO_WARDEN_SPAWNING, false);
        world.setGameRule(GameRule.DO_INSOMNIA, false);
    }

    /**
     * Removes vanilla/untagged hostiles already in the arena so players do not
     * teleport onto magma cubes, creepers, or other leftovers.
     */
    public static void clearUntaggedHostiles(World world, Arena arena) {
        if (world == null || arena == null) {
            return;
        }
        double cx = arena.getCenterX();
        double cz = arena.getCenterZ();
        double maxDist = arena.getRadius() + 5;
        for (Entity entity : world.getEntities()) {
            if (!isHostileMob(entity) || !(entity instanceof LivingEntity living)) {
                continue;
            }
            if (!isNearArena(entity.getLocation(), cx, cz, maxDist)) {
                continue;
            }
            if (isPluginManagedMob(living)) {
                continue;
            }
            living.remove();
        }
    }

    private static boolean isPluginManagedMob(LivingEntity living) {
        return isPhaseMob(living)
                || isSettlementMob(living)
                || isArcticMob(living)
                || isBeastForm(living)
                || isPlayerPilotGuardian(living)
                || living.getPersistentDataContainer().has(SIMULATION_TARGET_KEY, PersistentDataType.STRING);
    }

    /** Marks a disguise entity as a cursed player's beast form; value = owner uuid. */
    public static void tagBeastForm(LivingEntity entity, java.util.UUID ownerUuid) {
        entity.getPersistentDataContainer().set(BEAST_FORM_KEY, PersistentDataType.STRING,
                ownerUuid.toString());
        entity.setRemoveWhenFarAway(false);
    }

    public static boolean isBeastForm(LivingEntity living) {
        return living.getPersistentDataContainer().has(BEAST_FORM_KEY, PersistentDataType.STRING);
    }

    public static void tagBeastCamera(ArmorStand stand, java.util.UUID ownerUuid) {
        stand.getPersistentDataContainer().set(BEAST_CAMERA_KEY, PersistentDataType.STRING,
                ownerUuid.toString());
        stand.setRemoveWhenFarAway(false);
    }

    public static boolean isBeastCamera(Entity entity) {
        return entity instanceof ArmorStand stand
                && stand.getPersistentDataContainer().has(BEAST_CAMERA_KEY, PersistentDataType.STRING);
    }

    /** Marks a projectile (or lingering cloud) as launched from inside a church. */
    public static void tagChurchProjectile(Entity entity) {
        entity.getPersistentDataContainer().set(CHURCH_PROJECTILE_KEY, PersistentDataType.BYTE, (byte) 1);
    }

    public static boolean isChurchProjectile(Entity entity) {
        return entity.getPersistentDataContainer().has(CHURCH_PROJECTILE_KEY, PersistentDataType.BYTE);
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

    public static void tagVolcanoPiglin(Plugin plugin, LivingEntity entity) {
        entity.getPersistentDataContainer().set(VOLCANO_PIGLIN_KEY, PersistentDataType.BYTE, (byte) 1);
        entity.getPersistentDataContainer().set(PHASE_MOB_KEY, PersistentDataType.BYTE, (byte) 1);
    }

    public static boolean isVolcanoPiglin(LivingEntity living) {
        return living.getPersistentDataContainer().has(VOLCANO_PIGLIN_KEY, PersistentDataType.BYTE);
    }

    public static void markVolcanoPiglinBuffed(LivingEntity living) {
        living.getPersistentDataContainer().set(VOLCANO_PIGLIN_BUFFED_KEY, PersistentDataType.BYTE, (byte) 1);
    }

    public static boolean isVolcanoPiglinBuffed(LivingEntity living) {
        return living.getPersistentDataContainer().has(VOLCANO_PIGLIN_BUFFED_KEY, PersistentDataType.BYTE);
    }

    public static void tagVolcanoPiglinHuntOwner(LivingEntity entity, UUID ownerId) {
        entity.getPersistentDataContainer().set(VOLCANO_PIGLIN_HUNT_OWNER_KEY,
                PersistentDataType.STRING, ownerId.toString());
    }

    public static UUID getVolcanoPiglinHuntOwner(LivingEntity living) {
        String raw = living.getPersistentDataContainer().get(
                VOLCANO_PIGLIN_HUNT_OWNER_KEY, PersistentDataType.STRING);
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    public static void tagVolcanoCreeper(Plugin plugin, LivingEntity entity) {
        entity.getPersistentDataContainer().set(VOLCANO_CREEPER_KEY, PersistentDataType.BYTE, (byte) 1);
        entity.getPersistentDataContainer().set(PHASE_MOB_KEY, PersistentDataType.BYTE, (byte) 1);
    }

    public static boolean isVolcanoCreeper(LivingEntity living) {
        return living.getPersistentDataContainer().has(VOLCANO_CREEPER_KEY, PersistentDataType.BYTE);
    }

    public static void tagRageCreeper(Plugin plugin, LivingEntity entity) {
        entity.getPersistentDataContainer().set(RAGE_CREEPER_KEY, PersistentDataType.BYTE, (byte) 1);
        entity.getPersistentDataContainer().set(PHASE_MOB_KEY, PersistentDataType.BYTE, (byte) 1);
    }

    public static boolean isRageCreeper(LivingEntity living) {
        return living.getPersistentDataContainer().has(RAGE_CREEPER_KEY, PersistentDataType.BYTE);
    }

    public static void tagRageGolem(Plugin plugin, LivingEntity entity) {
        entity.getPersistentDataContainer().set(RAGE_GOLEM_KEY, PersistentDataType.BYTE, (byte) 1);
        entity.getPersistentDataContainer().set(PHASE_MOB_KEY, PersistentDataType.BYTE, (byte) 1);
    }

    public static boolean isRageGolem(LivingEntity living) {
        return living.getPersistentDataContainer().has(RAGE_GOLEM_KEY, PersistentDataType.BYTE);
    }

    public static void markRageUnitBuffed(LivingEntity living) {
        living.getPersistentDataContainer().set(RAGE_UNIT_BUFFED_KEY, PersistentDataType.BYTE, (byte) 1);
    }

    public static boolean isRageUnitBuffed(LivingEntity living) {
        return living.getPersistentDataContainer().has(RAGE_UNIT_BUFFED_KEY, PersistentDataType.BYTE);
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

    /** Settlement mobs (outpost pillagers, village villagers) persist and never count as phase mobs. */
    public static void tagSettlementMob(Plugin plugin, LivingEntity entity) {
        entity.getPersistentDataContainer().set(SETTLEMENT_MOB_KEY, PersistentDataType.BYTE, (byte) 1);
        entity.setRemoveWhenFarAway(false);
    }

    public static boolean isSettlementMob(LivingEntity living) {
        return living.getPersistentDataContainer().has(SETTLEMENT_MOB_KEY, PersistentDataType.BYTE);
    }

    public static void tagArcticMob(Plugin plugin, LivingEntity entity) {
        entity.getPersistentDataContainer().set(ARCTIC_MOB_KEY, PersistentDataType.BYTE, (byte) 1);
        entity.setRemoveWhenFarAway(false);
    }

    public static boolean isArcticMob(LivingEntity living) {
        return living.getPersistentDataContainer().has(ARCTIC_MOB_KEY, PersistentDataType.BYTE);
    }

    public static void tagIceFactionMob(Plugin plugin, LivingEntity entity) {
        entity.getPersistentDataContainer().set(FACTION_ICE_KEY, PersistentDataType.BYTE, (byte) 1);
        tagPhaseMob(plugin, entity);
    }

    public static boolean isIceFactionMob(LivingEntity living) {
        return living.getPersistentDataContainer().has(FACTION_ICE_KEY, PersistentDataType.BYTE);
    }

    public static void tagVolcanoFactionMob(Plugin plugin, LivingEntity entity) {
        entity.getPersistentDataContainer().set(FACTION_VOLCANO_KEY, PersistentDataType.BYTE, (byte) 1);
        tagVolcanoMob(plugin, entity);
    }

    public static boolean isVolcanoFactionMob(LivingEntity living) {
        return living.getPersistentDataContainer().has(FACTION_VOLCANO_KEY, PersistentDataType.BYTE);
    }

    /** Faction army plus piglin horde — ice units hunt both. */
    public static boolean isVolcanoAlignedMob(LivingEntity living) {
        return isVolcanoFactionMob(living) || isVolcanoPiglin(living);
    }

    public static void tagComet(Plugin plugin, Entity entity) {
        entity.getPersistentDataContainer().set(COMET_KEY, PersistentDataType.BYTE, (byte) 1);
    }

    public static boolean isCometEntity(Entity entity) {
        return entity.getPersistentDataContainer().has(COMET_KEY, PersistentDataType.BYTE);
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
            if (isCometEntity(entity)) {
                entity.remove();
                continue;
            }
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
                continue;
            }
            if (entity instanceof LivingEntity living && isSettlementMob(living)) {
                entity.remove();
                continue;
            }
            if (entity instanceof LivingEntity living && isArcticMob(living)) {
                entity.remove();
                continue;
            }
            if (isBeastCamera(entity)) {
                entity.remove();
                continue;
            }
            if (entity instanceof LivingEntity living && isBeastForm(living)) {
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

    public static int countFactionMobs(World world, Arena arena, boolean iceFaction) {
        if (world == null || arena == null) {
            return 0;
        }
        double cx = arena.getCenterX();
        double cz = arena.getCenterZ();
        double maxDist = arena.getRadius() + 5;
        int count = 0;
        for (Entity entity : world.getEntities()) {
            if (!(entity instanceof LivingEntity living) || living.isDead()) {
                continue;
            }
            if (!isNearArena(entity.getLocation(), cx, cz, maxDist)) {
                continue;
            }
            if (iceFaction ? isIceFactionMob(living) : isVolcanoFactionMob(living)) {
                count++;
            }
        }
        return count;
    }

    public static int countVolcanoTnt(World world, Arena arena) {
        if (world == null || arena == null) {
            return 0;
        }
        double cx = arena.getCenterX();
        double cz = arena.getCenterZ();
        double maxDist = arena.getRadius() + 5;
        int count = 0;
        for (Entity entity : world.getEntitiesByClass(TNTPrimed.class)) {
            if (!isNearArena(entity.getLocation(), cx, cz, maxDist)) {
                continue;
            }
            if (isVolcanoTnt((TNTPrimed) entity)) {
                count++;
            }
        }
        return count;
    }

    /**
     * True for hostiles that must survive the peaceful opening (simulation dummy,
     * cursed beast forms). Villagers are not {@link Enemy} and are left alone.
     */
    public static boolean isPeacefulStartExempt(LivingEntity living) {
        return isBeastForm(living)
                || isPlayerPilotGuardian(living)
                || living.getPersistentDataContainer().has(SIMULATION_TARGET_KEY, PersistentDataType.STRING);
    }

    /**
     * Removes every hostile in the arena except peaceful-start exempts. Used at
     * grace and FOREST start so leftover or plugin-spawned armies are gone
     * before the volcano phase.
     */
    public static void clearPeacefulStartHostiles(World world, Arena arena) {
        if (world == null || arena == null) {
            return;
        }
        double cx = arena.getCenterX();
        double cz = arena.getCenterZ();
        double maxDist = arena.getRadius() + 5;
        for (Entity entity : world.getEntities()) {
            if (!isHostileMob(entity) || !(entity instanceof LivingEntity living)) {
                continue;
            }
            if (!isNearArena(entity.getLocation(), cx, cz, maxDist)) {
                continue;
            }
            if (isPeacefulStartExempt(living)) {
                continue;
            }
            living.remove();
        }
    }

    private static boolean isNearArena(org.bukkit.Location loc, double cx, double cz, double maxDist) {
        double dx = loc.getX() - cx;
        double dz = loc.getZ() - cz;
        return dx * dx + dz * dz <= maxDist * maxDist;
    }
}
