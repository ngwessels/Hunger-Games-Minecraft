package com.hungergames.terrain;

import com.hungergames.HungerGamesPlugin;
import com.hungergames.arena.Arena;
import com.hungergames.game.Game;
import com.hungergames.game.GameState;
import com.hungergames.gamemode.TerrainPhaseConfig;
import com.hungergames.util.ArenaBounds;
import com.hungergames.util.ArenaEntityUtil;
import com.hungergames.util.CircleUtil;
import com.hungergames.util.EnderDragonMovement;
import com.hungergames.util.ParticleUtil;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.Random;

/**
 * Rodan-style volcano guardian: an invulnerable ender dragon that patrols the caldera,
 * chases players, and attacks with breath, swoops, and body contact.
 */
public class VolcanoGuardian {

    private enum FlightMode {
        PATROL,
        CHASE,
        RETURN
    }

    private final HungerGamesPlugin plugin;
    private final Random random = new Random();

    private Game game;
    private Arena arena;
    private EnderDragon dragon;
    private BukkitTask task;
    private boolean active;

    private double volcanoCenterX;
    private double volcanoCenterZ;
    private int volcanoPeakY;
    private int baseSurfaceY;

    private Location location;
    private FlightMode flightMode = FlightMode.PATROL;
    private double patrolAngle;
    private Player chaseTarget;
    private long swoopEndMs;
    private long lastBreathMs;
    private long lastSwoopMs;
    private long lastContactMs;
    private long lastGrowlMs;
    private long spawnTimeMs;

    private double guardianScale;
    private boolean invulnerable;
    private double altitudeAbovePeak;
    private double awayAltitudeAboveGround;
    private double awayBlendRadius;
    private double patrolRadiusMin;
    private double patrolRadiusMax;
    private double patrolSpeed;
    private double moveSpeed;
    private double playerFollowRadius;
    private double returnToVolcanoRadius;
    private double minDistanceFromVent;
    private double breathDamage;
    private double breathRadius;
    private long breathIntervalMs;
    private double breathMaxRange;
    private double swoopDamage;
    private long swoopCooldownMs;
    private double swoopSpeedMultiplier;
    private double contactDamage;
    private double contactRadius;
    private long contactIntervalMs;
    private TerrainPhaseConfig phaseConfig;

    public VolcanoGuardian(HungerGamesPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean isActive() {
        return active && dragon != null && !dragon.isDead();
    }

    /**
     * A valid chase target must be online, living, and an active participant.
     * Dead-but-not-yet-eliminated players (awaiting respawn) and players shielded
     * by respawn grace are excluded so the guardian drops aggro on death instead
     * of camping the corpse and re-engaging the player when they respawn.
     */
    private boolean isValidChaseTarget(Player player) {
        if (player == null || !player.isOnline() || player.isDead()) {
            return false;
        }
        if (game == null || game.getSession(player.getUniqueId()) == null) {
            return false;
        }
        return !plugin.getGameManager().isInRespawnGrace(game, player);
    }

    public void configure(TerrainPhaseConfig config) {
        this.phaseConfig = config;
        guardianScale = Math.max(0.5, config.getDouble("volcanoGuardianScale", 1.75));
        invulnerable = config.getInt("volcanoGuardianInvulnerable", 1) != 0;
        altitudeAbovePeak = config.getDouble("volcanoGuardianAltitudeAbovePeak", 15.0);
        awayAltitudeAboveGround = config.getDouble("volcanoGuardianAwayAltitudeAboveGround", 12.0);
        patrolRadiusMin = config.getDouble("volcanoGuardianPatrolRadiusMin", 28.0);
        patrolRadiusMax = Math.max(patrolRadiusMin, config.getDouble("volcanoGuardianPatrolRadiusMax", 52.0));
        patrolSpeed = config.getDouble("volcanoGuardianPatrolSpeed", 0.28);
        moveSpeed = config.getDouble("volcanoGuardianMoveSpeed", 0.48);
        playerFollowRadius = config.getDouble("volcanoGuardianPlayerFollowRadius", 85.0);
        returnToVolcanoRadius = config.getDouble("volcanoGuardianReturnToVolcanoRadius", 100.0);
        minDistanceFromVent = config.getDouble("volcanoGuardianMinDistanceFromVent", 22.0);
        awayBlendRadius = Math.max(minDistanceFromVent,
                config.getDouble("volcanoGuardianAwayBlendRadius", 90.0));
        breathDamage = config.getDouble("volcanoGuardianBreathDamage", 5.0);
        breathRadius = config.getDouble("volcanoGuardianBreathRadius", 4.0);
        breathIntervalMs = config.getInt("volcanoGuardianBreathIntervalSeconds", 6) * 1000L;
        breathMaxRange = config.getDouble("volcanoGuardianBreathMaxRange", 24.0);
        swoopDamage = config.getDouble("volcanoGuardianSwoopDamage", 10.0);
        swoopCooldownMs = config.getInt("volcanoGuardianSwoopCooldownSeconds", 14) * 1000L;
        swoopSpeedMultiplier = config.getDouble("volcanoGuardianSwoopSpeedMultiplier", 1.85);
        contactDamage = config.getDouble("volcanoGuardianContactDamage", 6.0);
        contactRadius = config.getDouble("volcanoGuardianContactRadius", 5.0);
        contactIntervalMs = config.getInt("volcanoGuardianContactIntervalTicks", 10) * 50L;
    }

    public void updateVolcanoPeakY(int peakY) {
        this.volcanoPeakY = peakY;
    }

    public void spawn(Game game, Arena arena, double centerX, double centerZ, int surfaceY, int peakY) {
        if (active) {
            return;
        }
        World world = arena.getWorld();
        if (world == null) {
            return;
        }

        this.game = game;
        this.arena = arena;
        this.volcanoCenterX = centerX;
        this.volcanoCenterZ = centerZ;
        this.baseSurfaceY = surfaceY;
        this.volcanoPeakY = peakY;
        this.patrolAngle = random.nextDouble() * Math.PI * 2;
        this.spawnTimeMs = System.currentTimeMillis();
        this.lastBreathMs = spawnTimeMs;
        this.lastSwoopMs = spawnTimeMs;
        this.lastContactMs = 0;
        this.lastGrowlMs = spawnTimeMs;

        double patrolRadius = (patrolRadiusMin + patrolRadiusMax) / 2.0;
        double spawnX = centerX + Math.cos(patrolAngle) * patrolRadius;
        double spawnZ = centerZ + Math.sin(patrolAngle) * patrolRadius;
        double spawnY = peakY + altitudeAbovePeak;
        location = new Location(world, spawnX, spawnY, spawnZ);

        ensureChunkLoaded(world);
        dragon = world.spawn(location, EnderDragon.class, entity -> {
            entity.setInvulnerable(invulnerable);
            entity.setRemoveWhenFarAway(false);
            AttributeInstance scale = entity.getAttribute(Attribute.SCALE);
            if (scale != null) {
                scale.setBaseValue(guardianScale);
            }
            ArenaEntityUtil.tagVolcanoGuardian(plugin, entity);
        });

        active = true;
        dragon.setPhase(EnderDragon.Phase.CIRCLING);
        EnderDragonMovement.steer(dragon, location, EnderDragonMovement.Style.PATROL);

        task = new BukkitRunnable() {
            @Override
            public void run() {
                if (!active || isGameOver()) {
                    destroy();
                    return;
                }
                if (dragon == null || dragon.isDead()) {
                    destroy();
                    return;
                }
                tick();
            }
        }.runTaskTimer(plugin, 1L, 1L);

        world.playSound(location, Sound.ENTITY_ENDER_DRAGON_GROWL, 3f, 0.65f);
        world.playSound(location, Sound.ENTITY_WITHER_SPAWN, 2f, 0.5f);
        spawnBurstParticles(world);
    }

    public void destroy() {
        active = false;
        if (task != null) {
            task.cancel();
            task = null;
        }
        if (dragon != null && !dragon.isDead()) {
            dragon.remove();
        }
        dragon = null;
        chaseTarget = null;
    }

    private boolean isGameOver() {
        if (game == null) {
            return true;
        }
        GameState state = game.getState();
        return state == GameState.ENDING || state == GameState.RESET || state == GameState.LOBBY;
    }

    private void tick() {
        World world = location.getWorld();
        if (world == null) {
            destroy();
            return;
        }

        ensureChunkLoaded(world);
        syncLocationFromDragon();
        updateFlightMode(world);

        Location flyTarget = computeFlyTarget(world);
        clampFlyTarget(flyTarget);
        enforceMinDistanceOnTarget(flyTarget);
        EnderDragonMovement.Style steerStyle = flightMode == FlightMode.CHASE
                ? EnderDragonMovement.Style.CHARGE
                : EnderDragonMovement.Style.PATROL;
        EnderDragonMovement.steer(dragon, flyTarget, steerStyle);
        syncLocationFromDragon();

        long now = System.currentTimeMillis();
        tickBreath(world, now);
        tickSwoopDamage(world, now);
        tickContactDamage(world, now);
        tickAmbientGrowl(world, now);
        spawnWingParticles(world);
    }

    private void updateFlightMode(World world) {
        // Drop aggro immediately if the current target died or is otherwise invalid,
        // otherwise the dragon keeps diving at the corpse and re-attacks on respawn.
        if (chaseTarget != null && !isValidChaseTarget(chaseTarget)) {
            chaseTarget = null;
            if (flightMode == FlightMode.CHASE) {
                flightMode = FlightMode.RETURN;
            }
        }

        Player nearest = findNearestAlivePlayer(world);
        if (nearest == null) {
            if (flightMode == FlightMode.CHASE) {
                flightMode = FlightMode.RETURN;
            } else if (flightMode == FlightMode.RETURN && distanceFromVolcano() <= minDistanceFromVent + 4) {
                flightMode = FlightMode.PATROL;
            }
            chaseTarget = null;
            return;
        }

        double distSq = horizontalDistanceSquared(nearest.getLocation());
        double followSq = playerFollowRadius * playerFollowRadius;
        double returnSq = returnToVolcanoRadius * returnToVolcanoRadius;

        if (distSq <= followSq) {
            flightMode = FlightMode.CHASE;
            chaseTarget = nearest;
            return;
        }

        if (flightMode == FlightMode.CHASE && distSq > returnSq) {
            flightMode = FlightMode.RETURN;
            chaseTarget = null;
            return;
        }

        if (flightMode == FlightMode.RETURN && distanceFromVolcano() <= minDistanceFromVent + 4) {
            flightMode = FlightMode.PATROL;
        }

        if (flightMode != FlightMode.CHASE) {
            chaseTarget = null;
        }
    }

    private void syncLocationFromDragon() {
        if (dragon == null || dragon.isDead()) {
            return;
        }
        Location actual = dragon.getLocation();
        location.setWorld(actual.getWorld());
        location.setX(actual.getX());
        location.setY(actual.getY());
        location.setZ(actual.getZ());
    }

    private Location computeFlyTarget(World world) {
        long now = System.currentTimeMillis();
        boolean swooping = now < swoopEndMs;
        Location target = location.clone();

        switch (flightMode) {
            case PATROL -> {
                patrolAngle += 0.018;
                double radius = patrolRadiusMin + (patrolRadiusMax - patrolRadiusMin)
                        * (0.5 + 0.5 * Math.sin(patrolAngle * 0.35));
                target.setX(volcanoCenterX + Math.cos(patrolAngle) * radius);
                target.setZ(volcanoCenterZ + Math.sin(patrolAngle) * radius);
                target.setY(cruiseAltitudeFor(world, target));
            }
            case CHASE -> {
                if (!isValidChaseTarget(chaseTarget)) {
                    chaseTarget = null;
                    flightMode = FlightMode.RETURN;
                    return computeReturnTarget(world, target);
                }
                if (!swooping && now - lastSwoopMs >= swoopCooldownMs && random.nextDouble() < 0.12) {
                    lastSwoopMs = now;
                    swoopEndMs = now + 1800L;
                    chaseTarget.getWorld().playSound(location, Sound.ENTITY_ENDER_DRAGON_FLAP, 1.8f, 0.7f);
                }
                Location playerLoc = chaseTarget.getLocation();
                target.setX(playerLoc.getX());
                target.setZ(playerLoc.getZ());
                if (swooping) {
                    target.setY(Math.max(baseSurfaceY + 6, playerLoc.getY() + 2));
                } else {
                    target.setY(Math.max(playerLoc.getY() + 8, cruiseAltitudeFor(world, target)));
                }
            }
            case RETURN -> target = computeReturnTarget(world, target);
        }
        return target;
    }

    private Location computeReturnTarget(World world, Location target) {
        double dx = volcanoCenterX - target.getX();
        double dz = volcanoCenterZ - target.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist > 0.01) {
            double lead = Math.min(dist, 30.0);
            target.setX(target.getX() + (dx / dist) * lead);
            target.setZ(target.getZ() + (dz / dist) * lead);
        }
        target.setY(cruiseAltitudeFor(world, target));
        return target;
    }

    /**
     * Height the guardian should hold at the given X/Z. At the caldera it flies
     * caldera-height (peak + altitudeAbovePeak); the farther out it roams, the
     * more that blends toward a terrain-following height so it can hunt low
     * across the map instead of circling at caldera altitude.
     */
    private double cruiseAltitudeFor(World world, Location target) {
        double dx = target.getX() - volcanoCenterX;
        double dz = target.getZ() - volcanoCenterZ;
        double dist = Math.sqrt(dx * dx + dz * dz);
        double calderaY = volcanoPeakY + altitudeAbovePeak;
        double blend = Math.min(1.0, dist / awayBlendRadius);
        double awayY = groundYAt(world, target) + awayAltitudeAboveGround;
        return calderaY * (1.0 - blend) + awayY * blend;
    }

    private int groundYAt(World world, Location target) {
        int highest = world.getHighestBlockYAt(target.getBlockX(), target.getBlockZ());
        return Math.max(baseSurfaceY, highest);
    }

    private void clampFlyTarget(Location target) {
        double playRadius = ArenaBounds.getPlayRadius(arena) - 6;
        double dist = CircleUtil.distanceFromCenter(
                arena.getCenterX(), arena.getCenterZ(), target.getX(), target.getZ());
        if (dist <= playRadius) {
            return;
        }
        Location clamped = CircleUtil.clampToCircle(
                arena.getCenterX(), arena.getCenterZ(), target, playRadius);
        target.setX(clamped.getX());
        target.setZ(clamped.getZ());
    }

    private void enforceMinDistanceOnTarget(Location target) {
        if (flightMode == FlightMode.CHASE) {
            return;
        }
        double dx = target.getX() - volcanoCenterX;
        double dz = target.getZ() - volcanoCenterZ;
        double distSq = dx * dx + dz * dz;
        double minDist = minDistanceFromVent;
        if (distSq >= minDist * minDist) {
            return;
        }
        double dist = Math.sqrt(distSq);
        if (dist < 0.01) {
            patrolAngle = random.nextDouble() * Math.PI * 2;
            target.setX(volcanoCenterX + Math.cos(patrolAngle) * minDist);
            target.setZ(volcanoCenterZ + Math.sin(patrolAngle) * minDist);
            return;
        }
        double scale = minDist / dist;
        target.setX(volcanoCenterX + dx * scale);
        target.setZ(volcanoCenterZ + dz * scale);
    }

    private void tickBreath(World world, long now) {
        if (phaseConfig == null || now - lastBreathMs < breathIntervalMs) {
            return;
        }
        if (flightMode != FlightMode.CHASE && !hasParticipantNearby(world, breathRadius * guardianScale * 2)) {
            return;
        }

        Location breathCenter = location.clone();
        if (flightMode == FlightMode.CHASE && isValidChaseTarget(chaseTarget)) {
            // Only breathe on the target when actually close to it; never snipe
            // from across the map while still flying in.
            if (location.distance(chaseTarget.getLocation()) > breathMaxRange) {
                return;
            }
            breathCenter = chaseTarget.getLocation().add(0, 1, 0);
        }

        lastBreathMs = now;
        VolcanoGuardianBreath.fire(game, world, breathCenter, phaseConfig, guardianScale, null);
    }

    private void tickSwoopDamage(World world, long now) {
        if (now >= swoopEndMs || flightMode != FlightMode.CHASE || !isValidChaseTarget(chaseTarget)) {
            return;
        }
        if (location.distance(chaseTarget.getLocation()) > contactRadius * guardianScale * 1.5) {
            return;
        }
        if (now - lastContactMs < contactIntervalMs) {
            return;
        }
        lastContactMs = now;
        chaseTarget.damage(swoopDamage);
        Vector knockback = chaseTarget.getLocation().toVector().subtract(location.toVector());
        knockback.setY(0.4);
        if (knockback.lengthSquared() > 0.01) {
            knockback.normalize().multiply(1.4);
            chaseTarget.setVelocity(knockback);
        }
        world.playSound(chaseTarget.getLocation(), Sound.ENTITY_PLAYER_ATTACK_STRONG, 1.2f, 0.6f);
    }

    private void tickContactDamage(World world, long now) {
        if (now - lastContactMs < contactIntervalMs) {
            return;
        }
        double radius = contactRadius * guardianScale;
        for (Player player : game.getAlivePlayers()) {
            if (!player.getWorld().equals(world)) {
                continue;
            }
            if (!isValidChaseTarget(player)) {
                continue;
            }
            if (player.getLocation().distance(location) > radius) {
                continue;
            }
            lastContactMs = now;
            player.damage(contactDamage);
            player.setFireTicks(Math.max(player.getFireTicks(), 40));
            Vector knockback = player.getLocation().toVector().subtract(location.toVector());
            knockback.setY(0.25);
            if (knockback.lengthSquared() > 0.01) {
                knockback.normalize().multiply(0.9);
                player.setVelocity(knockback);
            }
        }
    }

    private void tickAmbientGrowl(World world, long now) {
        if (now - lastGrowlMs < 14000L) {
            return;
        }
        lastGrowlMs = now;
        world.playSound(location, Sound.ENTITY_ENDER_DRAGON_AMBIENT, 1.6f, 0.55f);
    }

    private void spawnWingParticles(World world) {
        if (random.nextDouble() > 0.35) {
            return;
        }
        ParticleUtil.spawn(world, Particle.SOUL_FIRE_FLAME,
                location.getX(), location.getY(), location.getZ(),
                4, 1.5, 0.6, 1.5, 0.01);
        ParticleUtil.spawn(world, Particle.LARGE_SMOKE,
                location.getX(), location.getY() - 1, location.getZ(),
                2, 1.0, 0.3, 1.0, 0.01);
    }

    private void spawnBurstParticles(World world) {
        ParticleUtil.spawn(world, Particle.FLAME, location, 80, 2.5, 1.5, 2.5, 0.08);
        ParticleUtil.spawn(world, Particle.LAVA, location, 20, 1.5, 1.0, 1.5, 0.02);
        ParticleUtil.spawn(world, Particle.DRAGON_BREATH, location, 40, 2.0, 1.0, 2.0, 0.03);
    }

    private boolean hasParticipantNearby(World world, double radius) {
        double radiusSq = radius * radius;
        for (Player player : game.getAlivePlayers()) {
            if (!player.getWorld().equals(world)) {
                continue;
            }
            if (!isValidChaseTarget(player)) {
                continue;
            }
            if (player.getLocation().distanceSquared(location) <= radiusSq) {
                return true;
            }
        }
        return false;
    }

    private Player findNearestAlivePlayer(World world) {
        Player nearest = null;
        double nearestDistSq = Double.MAX_VALUE;
        for (Player player : game.getAlivePlayers()) {
            if (!player.getWorld().equals(world)) {
                continue;
            }
            if (!isValidChaseTarget(player)) {
                continue;
            }
            double distSq = player.getLocation().distanceSquared(location);
            if (distSq < nearestDistSq) {
                nearestDistSq = distSq;
                nearest = player;
            }
        }
        return nearest;
    }

    private double horizontalDistanceSquared(Location other) {
        double dx = other.getX() - location.getX();
        double dz = other.getZ() - location.getZ();
        return dx * dx + dz * dz;
    }

    private double distanceFromVolcano() {
        double dx = location.getX() - volcanoCenterX;
        double dz = location.getZ() - volcanoCenterZ;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private void ensureChunkLoaded(World world) {
        Chunk chunk = world.getChunkAt(location.getBlockX() >> 4, location.getBlockZ() >> 4);
        if (!chunk.isLoaded()) {
            chunk.load(true);
        }
    }
}
