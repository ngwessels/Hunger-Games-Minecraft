package com.hungergames.terrain;

import com.hungergames.HungerGamesPlugin;
import com.hungergames.arena.Arena;
import com.hungergames.arena.VolcanoCaldera;
import com.hungergames.game.Game;
import com.hungergames.gamemode.TerrainPhaseConfig;
import com.hungergames.util.ArenaEntityUtil;
import com.hungergames.util.CircleUtil;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Wolf;
import org.bukkit.entity.PolarBear;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Two opposing mob armies whose spawn caps and cadence follow cold/tundra vs
 * volcano dominance. The "ice" roster lives on remaining tundra (and the ice
 * ring); volcano units live on nether ground. They hunt each other first,
 * players second.
 */
public class VolcanoBiomeFactions {

    private static final EntityType[] DEFAULT_ICE = {
            EntityType.POLAR_BEAR, EntityType.STRAY, EntityType.WOLF
    };
    private static final EntityType[] DEFAULT_VOLCANO = {
            EntityType.BLAZE, EntityType.MAGMA_CUBE, EntityType.WITHER_SKELETON,
            EntityType.PIGLIN_BRUTE, EntityType.HOGLIN
    };

    private final HungerGamesPlugin plugin;
    private final Random random = new Random();

    public VolcanoBiomeFactions(HungerGamesPlugin plugin) {
        this.plugin = plugin;
    }

    public static boolean isEnabled(TerrainPhaseConfig config) {
        return config != null && config.getBoolean("factionWarEnabled", true);
    }

    public void tick(Game game, Arena arena, World world, TerrainPhaseConfig config,
                     VolcanoBiomeWar biomeWar, VolcanoNetherSpread netherSpread) {
        if (world == null || biomeWar == null || !isEnabled(config)) {
            return;
        }
        double volcano = biomeWar.getVolcanicDominance();
        double ice = biomeWar.getIceDominance();
        int iceMax = lerpCap(config, "iceFactionMaxMobsStart", 12, "iceFactionMaxMobsEnd", 0, volcano);
        int volcanoMax = lerpCap(config, "volcanoFactionMaxMobsStart", 3, "volcanoFactionMaxMobsEnd", 14, volcano);

        cullOverCap(world, arena, true, iceMax);
        cullOverCap(world, arena, false, volcanoMax);

        trySpawnFaction(world, arena, config, netherSpread, true, ice, iceMax,
                config.getDouble("iceFactionSpawnChancePerTick", 0.12));
        trySpawnFaction(world, arena, config, netherSpread, false, volcano, volcanoMax,
                config.getDouble("volcanoFactionSpawnChancePerTick", 0.10));

        retargetFactions(game, world, arena, config);
    }

    /** Linear cap from match-start value to match-end value using volcanic dominance. */
    private int lerpCap(TerrainPhaseConfig config, String startKey, int startDefault,
                        String endKey, int endDefault, double volcanicDominance) {
        int start = Math.max(0, config.getInt(startKey, startDefault));
        int end = Math.max(0, config.getInt(endKey, endDefault));
        return (int) Math.round(start + (end - start) * volcanicDominance);
    }

    private void trySpawnFaction(World world, Arena arena, TerrainPhaseConfig config,
                                 VolcanoNetherSpread netherSpread, boolean iceFaction,
                                 double strength, int max, double chance) {
        if (max <= 0 || strength <= 0.02) {
            return;
        }
        int current = ArenaEntityUtil.countFactionMobs(world, arena, iceFaction);
        if (current >= max) {
            return;
        }
        if (random.nextDouble() > chance * (0.35 + strength)) {
            return;
        }
        EntityType type = pickType(config, iceFaction);
        if (type == null) {
            return;
        }
        Location spawn = findSpawn(world, arena, netherSpread, iceFaction, config);
        if (spawn == null) {
            return;
        }
        LivingEntity entity = (LivingEntity) world.spawnEntity(spawn, type);
        prepareMob(entity, iceFaction, type, config);
        if (iceFaction) {
            ArenaEntityUtil.tagIceFactionMob(plugin, entity);
        } else {
            ArenaEntityUtil.tagVolcanoFactionMob(plugin, entity);
        }
    }

    private EntityType pickType(TerrainPhaseConfig config, boolean iceFaction) {
        List<String> names = config.getStringList(iceFaction ? "iceFactionMobs" : "volcanoFactionMobs");
        List<EntityType> pool = new ArrayList<>();
        if (!names.isEmpty()) {
            for (String name : names) {
                try {
                    EntityType type = EntityType.valueOf(name.toUpperCase());
                    pool.add(type);
                } catch (IllegalArgumentException ignored) {
                    // skip unknown names so a typo does not break spawning
                }
            }
        }
        if (pool.isEmpty()) {
            EntityType[] defaults = iceFaction ? DEFAULT_ICE : DEFAULT_VOLCANO;
            for (EntityType type : defaults) {
                double weight = config.getDouble(type.name() + "_spawnWeight", 1.0);
                if (weight > 0) {
                    pool.add(type);
                }
            }
        }
        if (pool.isEmpty()) {
            return null;
        }
        return pool.get(random.nextInt(pool.size()));
    }

    private Location findSpawn(World world, Arena arena, VolcanoNetherSpread netherSpread,
                               boolean iceFaction, TerrainPhaseConfig config) {
        double cx = arena.getCenterX();
        double cz = arena.getCenterZ();
        // Ice/tundra faction prefers beyond the plains lead so it stays on cold ground.
        int plainsLead = Math.max(0, config.getInt("biomeWarPlainsLeadBlocks", 48));
        int minR = iceFaction
                ? Math.max(VolcanoCaldera.NO_TREE_RADIUS + 4,
                netherSpread.getConvertedRadius() + plainsLead + 2)
                : VolcanoCaldera.NO_TREE_RADIUS;
        int maxR = iceFaction
                ? arena.getRadius() - 4
                : Math.max(minR + 1, netherSpread.getConvertedRadius());
        if (minR >= maxR) {
            if (iceFaction) {
                return null;
            }
            maxR = minR + 8;
        }
        int baseY = (int) arena.getCenterY();
        Location fallback = null;
        for (int attempt = 0; attempt < 20; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double dist = minR + random.nextDouble() * Math.max(1, maxR - minR);
            int x = (int) (cx + Math.cos(angle) * dist);
            int z = (int) (cz + Math.sin(angle) * dist);
            if (!CircleUtil.isInsideCircle(cx, cz, x, z, arena.getRadius() - 2)) {
                continue;
            }
            boolean volcanic = netherSpread.isConvertedGround(world, x, z);
            if (iceFaction == volcanic) {
                continue;
            }
            int y = VolcanoHeatUtil.findTerrainGroundY(world, x, z, baseY);
            Block ground = world.getBlockAt(x, y, z);
            Material above = world.getBlockAt(x, y + 1, z).getType();
            if (!ground.getType().isSolid() || ground.isLiquid()) {
                continue;
            }
            if (!above.isAir() && above != Material.SNOW && above != Material.SHORT_GRASS
                    && above != Material.FERN && above != Material.DEAD_BUSH) {
                continue;
            }
            Location loc = new Location(world, x + 0.5, y + 1, z + 0.5);
            if (!iceFaction || isColdFactionGround(ground.getType())) {
                return loc;
            }
            if (fallback == null) {
                fallback = loc;
            }
        }
        return fallback;
    }

    private static boolean isColdFactionGround(Material type) {
        return type == Material.SNOW_BLOCK
                || type == Material.GRASS_BLOCK
                || type == Material.PODZOL
                || type == Material.COARSE_DIRT
                || type == Material.ICE
                || type == Material.PACKED_ICE
                || type == Material.BLUE_ICE;
    }

    private void prepareMob(LivingEntity entity, boolean iceFaction, EntityType type,
                            TerrainPhaseConfig config) {
        entity.setRemoveWhenFarAway(true);
        double follow = iceFaction
                ? config.getDouble("iceFactionFollowRange", 40.0)
                : config.getDouble("volcanoFactionFollowRange", 36.0);
        AttributeInstance range = entity.getAttribute(Attribute.FOLLOW_RANGE);
        if (range != null) {
            range.setBaseValue(Math.max(16.0, follow));
        }
        if (entity instanceof PolarBear bear) {
            bear.setAdult();
        }
        if (entity instanceof Wolf wolf) {
            wolf.setAdult();
            wolf.setAngry(true);
        }
        if (type == EntityType.HOGLIN && entity instanceof org.bukkit.entity.Hoglin hoglin) {
            hoglin.setImmuneToZombification(true);
            hoglin.setAdult();
        }
        if (type == EntityType.PIGLIN_BRUTE && entity instanceof org.bukkit.entity.PiglinBrute brute) {
            brute.setImmuneToZombification(true);
            brute.setBaby(false);
        }
    }

    private void cullOverCap(World world, Arena arena, boolean iceFaction, int max) {
        int extra = ArenaEntityUtil.countFactionMobs(world, arena, iceFaction) - max;
        if (extra <= 0) {
            return;
        }
        for (Entity entity : world.getEntities()) {
            if (extra <= 0) {
                break;
            }
            if (!(entity instanceof LivingEntity living) || living.isDead()) {
                continue;
            }
            if (iceFaction ? !ArenaEntityUtil.isIceFactionMob(living)
                    : !ArenaEntityUtil.isVolcanoFactionMob(living)) {
                continue;
            }
            if (!isNearArena(living, arena)) {
                continue;
            }
            living.remove();
            extra--;
        }
    }

    private void retargetFactions(Game game, World world, Arena arena, TerrainPhaseConfig config) {
        double range = Math.max(12.0, config.getDouble("factionAggroRange", 28.0));
        double rangeSq = range * range;
        for (Entity entity : world.getEntities()) {
            if (!(entity instanceof Mob mob) || mob.isDead()) {
                continue;
            }
            boolean ice = ArenaEntityUtil.isIceFactionMob(mob);
            boolean volcano = ArenaEntityUtil.isVolcanoFactionMob(mob);
            if (!ice && !volcano) {
                continue;
            }
            if (ArenaEntityUtil.getVolcanoPiglinHuntOwner(mob) != null) {
                continue;
            }
            LivingEntity current = mob.getTarget();
            if (isValidFactionTarget(current, ice) || isAlivePlayer(game, current)) {
                continue;
            }
            LivingEntity rival = nearestRival(world, arena, mob, ice, rangeSq);
            if (rival != null) {
                mob.setTarget(rival);
                continue;
            }
            Player player = nearestPlayer(game, mob.getLocation(), rangeSq);
            if (player != null) {
                mob.setTarget(player);
            }
        }
    }

    private static boolean isValidFactionTarget(LivingEntity target, boolean seekerIsIce) {
        if (target == null || target.isDead()) {
            return false;
        }
        return seekerIsIce
                ? ArenaEntityUtil.isVolcanoAlignedMob(target)
                : ArenaEntityUtil.isIceFactionMob(target);
    }

    private static boolean isAlivePlayer(Game game, LivingEntity target) {
        return target instanceof Player player
                && player.isOnline()
                && !player.isDead()
                && game.getAlivePlayers().contains(player);
    }

    private LivingEntity nearestRival(World world, Arena arena, Mob seeker, boolean seekerIsIce,
                                      double rangeSq) {
        LivingEntity best = null;
        double bestDist = rangeSq;
        Location from = seeker.getLocation();
        for (Entity entity : world.getEntities()) {
            if (!(entity instanceof LivingEntity living) || living.isDead() || living == seeker) {
                continue;
            }
            if (seekerIsIce ? !ArenaEntityUtil.isVolcanoAlignedMob(living)
                    : !ArenaEntityUtil.isIceFactionMob(living)) {
                continue;
            }
            if (!isNearArena(living, arena)) {
                continue;
            }
            double dist = living.getLocation().distanceSquared(from);
            if (dist < bestDist) {
                bestDist = dist;
                best = living;
            }
        }
        return best;
    }

    private static Player nearestPlayer(Game game, Location from, double rangeSq) {
        Player best = null;
        double bestDist = rangeSq;
        for (Player player : game.getAlivePlayers()) {
            if (player == null || !player.isOnline() || player.isDead()) {
                continue;
            }
            if (from.getWorld() == null || player.getWorld() != from.getWorld()) {
                continue;
            }
            double dist = player.getLocation().distanceSquared(from);
            if (dist <= bestDist) {
                bestDist = dist;
                best = player;
            }
        }
        return best;
    }

    private static boolean isNearArena(LivingEntity living, Arena arena) {
        Location loc = living.getLocation();
        double dx = loc.getX() - arena.getCenterX();
        double dz = loc.getZ() - arena.getCenterZ();
        double max = arena.getRadius() + 5.0;
        return dx * dx + dz * dz <= max * max;
    }
}
