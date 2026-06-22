package com.hungergames.terrain;

import com.hungergames.HungerGamesPlugin;
import com.hungergames.arena.Arena;
import com.hungergames.game.Game;
import com.hungergames.game.GameState;
import com.hungergames.gamemode.TerrainPhaseConfig;
import com.hungergames.util.ArenaBounds;
import com.hungergames.util.ArenaEntityUtil;
import com.hungergames.util.CircleUtil;
import com.hungergames.util.ParticleUtil;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.Random;
import java.util.function.Consumer;

public class FireTornado {

    private final HungerGamesPlugin plugin;
    private final Random random = new Random();

    private Game game;
    private Arena arena;
    private double volcanoCenterX;
    private double volcanoCenterZ;
    private int baseSurfaceY;

    private Location location;
    private Vector direction;
    private double spinAngle;
    private long lastDirectionChangeMs;
    private long lastDamageMs;
    private long lastLightningMs;
    private long spawnTimeMs;
    private int volcanoGrowthRadius;
    private int volcanoPeakY;
    private ArmorStand anchor;
    private BukkitTask task;
    private boolean active;

    private double horizontalRadius;
    private double radiusMin;
    private double radiusMax;
    private int columnHeight;
    private double moveSpeed;
    private double damage;
    private long damageIntervalMs;
    private int travelMarginBeyondGrowth;
    private int lifetimeSeconds;
    private long lightningMinIntervalMs;
    private long lightningMaxIntervalMs;
    private double lightningDamage;
    private double lightningRealStrikeThreshold;
    private double burnChancePerTick;
    private double blockDestroyChancePerTick;
    private double playerFollowRadius;

    private static final BlockFace[] EMBER_FACES = {
            BlockFace.UP, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST
    };

    public FireTornado(HungerGamesPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean isActive() {
        return active;
    }

    /** True when a block sits inside this tornado's burn column (arena burn acceleration skips these). */
    public boolean containsBlock(int x, int y, int z) {
        if (!active || location == null) {
            return false;
        }
        double dx = x + 0.5 - location.getX();
        double dz = z + 0.5 - location.getZ();
        if (dx * dx + dz * dz > horizontalRadius * horizontalRadius) {
            return false;
        }
        int baseY = location.getBlockY();
        int scanHeight = Math.min(columnHeight, 20);
        return y >= baseY && y < baseY + scanHeight;
    }

    public void configure(TerrainPhaseConfig config) {
        radiusMin = config.getDouble("fireTornadoRadiusMin", 6.0);
        radiusMax = Math.max(radiusMin, config.getDouble("fireTornadoRadiusMax", 12.0));
        horizontalRadius = radiusMin;
        columnHeight = Math.max(50, config.getInt("fireTornadoHeight", 50));
        moveSpeed = config.getDouble("fireTornadoMoveSpeed", 0.42);
        damage = config.getDouble("fireTornadoDamage", 3.0);
        damageIntervalMs = config.getInt("fireTornadoDamageIntervalTicks", 20) * 50L;
        travelMarginBeyondGrowth = config.getInt("fireTornadoMaxBeyondGrowth", 20);
        lifetimeSeconds = config.getInt("fireTornadoLifetimeSeconds", 90);
        lightningMinIntervalMs = config.getInt("fireTornadoLightningMinIntervalMs", 500);
        lightningMaxIntervalMs = config.getInt("fireTornadoLightningMaxIntervalMs", 3000);
        lightningDamage = config.getDouble("fireTornadoLightningDamage", 2.5);
        lightningRealStrikeThreshold = config.getDouble("fireTornadoLightningRealStrikeThreshold", 0.6);
        burnChancePerTick = config.getDouble("fireTornadoBurnChancePerTick", 0.4);
        blockDestroyChancePerTick = config.getDouble("fireTornadoBlockDestroyChancePerTick", 0.15);
        playerFollowRadius = config.getDouble("fireTornadoPlayerFollowRadius", 75.0);
    }

    public void spawn(Game game, Arena arena, Location origin, double centerX, double centerZ,
                      int surfaceY, int growthRadius, int peakY) {
        if (active) {
            return;
        }
        this.game = game;
        this.arena = arena;
        this.volcanoCenterX = centerX;
        this.volcanoCenterZ = centerZ;
        this.baseSurfaceY = surfaceY;
        this.volcanoGrowthRadius = growthRadius;
        this.volcanoPeakY = peakY;
        this.location = origin.clone();
        this.direction = directionAwayFromVolcano(Math.PI / 3);
        this.spinAngle = random.nextDouble() * Math.PI * 2;
        this.lastDirectionChangeMs = System.currentTimeMillis();
        this.lastDamageMs = 0;
        this.lastLightningMs = 0;
        this.spawnTimeMs = System.currentTimeMillis();
        this.active = true;

        World world = origin.getWorld();
        alignToSurface(world);
        ensureChunkLoaded(world);

        anchor = world.spawn(location, ArmorStand.class, stand -> {
            stand.setVisible(false);
            stand.setGravity(false);
            stand.setInvulnerable(true);
            stand.setMarker(true);
            stand.setSilent(true);
            stand.setCollidable(false);
            stand.getPersistentDataContainer().set(
                    ArenaEntityUtil.FIRE_TORNADO_KEY, PersistentDataType.BYTE, (byte) 1);
        });

        task = new BukkitRunnable() {
            @Override
            public void run() {
                if (!active || isGameOver()) {
                    destroy();
                    return;
                }
                tick();
            }
        }.runTaskTimer(plugin, 1L, 2L);

        world.playSound(location, Sound.ENTITY_BLAZE_SHOOT, 2f, 0.5f);
        world.playSound(location, Sound.ENTITY_WITHER_SHOOT, 1.5f, 0.6f);
        world.playSound(location, Sound.ENTITY_GENERIC_EXPLODE, 1.2f, 0.8f);
        spawnParticles(world);
    }

    public void updateGrowthRadius(int growthRadius, int peakY) {
        this.volcanoGrowthRadius = growthRadius;
        this.volcanoPeakY = peakY;
    }

    public void destroy() {
        active = false;
        if (task != null) {
            task.cancel();
            task = null;
        }
        if (anchor != null && !anchor.isDead()) {
            anchor.remove();
            anchor = null;
        }
    }

    private boolean isGameOver() {
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
        updateRadius();
        boolean chasingPlayer = updateDirectionTowardPlayer(world);
        if (!chasingPlayer) {
            maybeChangeDirection();
        }
        double moveDistance = moveTornado(world, chasingPlayer);
        alignToSurface(world);

        if (isOutsidePlayArea()) {
            world.playSound(location, Sound.BLOCK_FIRE_EXTINGUISH, 1.5f, 0.7f);
            destroy();
            return;
        }

        long aliveSec = (System.currentTimeMillis() - spawnTimeMs) / 1000L;
        if (lifetimeSeconds > 0 && aliveSec >= lifetimeSeconds) {
            world.playSound(location, Sound.BLOCK_FIRE_EXTINGUISH, 1.5f, 0.7f);
            destroy();
            return;
        }

        if (anchor != null && !anchor.isDead()) {
            anchor.teleport(location);
        }

        spinAngle += 0.65;
        spawnParticles(world);
        tickLightning(world, moveDistance);
        tickBlockEffects(world);
        affectEntities(world);

        if (aliveSec % 3 == 0) {
            world.playSound(location, Sound.ENTITY_BLAZE_AMBIENT, 1.5f, 0.6f);
        }
    }

    private void ensureChunkLoaded(World world) {
        Chunk chunk = world.getChunkAt(location.getBlockX() >> 4, location.getBlockZ() >> 4);
        if (!chunk.isLoaded()) {
            chunk.load(true);
        }
    }

    private void maybeChangeDirection() {
        long now = System.currentTimeMillis();
        if (now - lastDirectionChangeMs > 3000 + random.nextInt(5000)) {
            direction = directionAwayFromVolcano(Math.PI / 2.5);
            lastDirectionChangeMs = now;
        }
    }

    private boolean updateDirectionTowardPlayer(World world) {
        if (playerFollowRadius <= 0) {
            return false;
        }
        Player target = findNearestAlivePlayerWithinFollowRange(world);
        if (target == null) {
            return false;
        }
        Vector toPlayer = target.getLocation().toVector().subtract(location.toVector());
        toPlayer.setY(0);
        if (toPlayer.lengthSquared() < 0.01) {
            return true;
        }
        direction = toPlayer.normalize();
        lastDirectionChangeMs = System.currentTimeMillis();
        return true;
    }

    private Player findNearestAlivePlayerWithinFollowRange(World world) {
        Player nearest = null;
        double nearestDistSq = playerFollowRadius * playerFollowRadius;
        for (Player player : game.getAlivePlayers()) {
            if (!player.getWorld().equals(world)) {
                continue;
            }
            double distSq = player.getLocation().distanceSquared(location);
            if (distSq <= nearestDistSq) {
                nearestDistSq = distSq;
                nearest = player;
            }
        }
        return nearest;
    }

    private void updateRadius() {
        double t = (System.currentTimeMillis() - spawnTimeMs) / 1000.0;
        // Two unsynced waves make the swelling feel organic instead of metronomic.
        double wave = 0.5 + 0.5 * (Math.sin(t * 0.9) * 0.65 + Math.sin(t * 0.37 + 1.7) * 0.35);
        horizontalRadius = radiusMin + (radiusMax - radiusMin) * Math.max(0, Math.min(1, wave));
    }

    private Vector randomHorizontalDirection() {
        double angle = random.nextDouble() * Math.PI * 2;
        return new Vector(Math.cos(angle), 0, Math.sin(angle)).normalize();
    }

    private Vector offsetFromVolcano() {
        return new Vector(location.getX() - volcanoCenterX, 0, location.getZ() - volcanoCenterZ);
    }

    private Vector directionAwayFromVolcano(double maxSpreadRadians) {
        Vector away = offsetFromVolcano();
        if (away.lengthSquared() < 0.01) {
            return randomHorizontalDirection();
        }
        away.normalize();
        double spread = (random.nextDouble() - 0.5) * maxSpreadRadians;
        double cos = Math.cos(spread);
        double sin = Math.sin(spread);
        double x = away.getX() * cos - away.getZ() * sin;
        double z = away.getX() * sin + away.getZ() * cos;
        return new Vector(x, 0, z).normalize();
    }

    private void ensureDirectionAwayFromVolcano() {
        Vector away = offsetFromVolcano();
        if (away.lengthSquared() < 0.01) {
            direction = randomHorizontalDirection();
            return;
        }
        double dot = direction.getX() * away.getX() + direction.getZ() * away.getZ();
        if (dot < 0) {
            direction = directionAwayFromVolcano(Math.PI / 6);
        }
    }

    private void enforceMinDistanceFromVolcano() {
        Vector away = offsetFromVolcano();
        double distSq = away.lengthSquared();
        double minDist = volcanoGrowthRadius + Math.max(8, travelMarginBeyondGrowth / 3);
        if (distSq >= minDist * minDist) {
            return;
        }
        double dist = Math.sqrt(distSq);
        if (dist < 0.01) {
            direction = randomHorizontalDirection();
            location.setX(volcanoCenterX + direction.getX() * minDist);
            location.setZ(volcanoCenterZ + direction.getZ() * minDist);
            return;
        }
        double scale = minDist / dist;
        location.setX(volcanoCenterX + away.getX() * scale);
        location.setZ(volcanoCenterZ + away.getZ() * scale);
        direction = away.multiply(1.0 / dist);
    }

    private boolean isOutsidePlayArea() {
        double playRadius = ArenaBounds.getPlayRadius(arena);
        double dist = CircleUtil.distanceFromCenter(
                arena.getCenterX(), arena.getCenterZ(),
                location.getX(), location.getZ());
        return dist + horizontalRadius > playRadius;
    }

    private double moveTornado(World world, boolean chasingPlayer) {
        double beforeX = location.getX();
        double beforeZ = location.getZ();
        if (!chasingPlayer) {
            ensureDirectionAwayFromVolcano();
        }
        location.add(direction.clone().multiply(moveSpeed));
        if (!chasingPlayer) {
            enforceMinDistanceFromVolcano();
        }
        double dx = location.getX() - beforeX;
        double dz = location.getZ() - beforeZ;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private double computeLightningIntensity(double moveDistance) {
        double spinWave = 0.5 + 0.5 * Math.sin(spinAngle * 0.4);
        double pulseWave = 0.5 + 0.5 * Math.sin((System.currentTimeMillis() - spawnTimeMs) / 380.0);
        long sinceDirectionChange = System.currentTimeMillis() - lastDirectionChangeMs;
        double surge = sinceDirectionChange < 2500
                ? 1.0 - sinceDirectionChange / 2500.0
                : 0.0;
        double moveBoost = Math.min(1.0, moveDistance / Math.max(0.01, moveSpeed * 1.4));
        return Math.min(1.0, Math.max(0.12,
                spinWave * 0.35 + pulseWave * 0.3 + surge * 0.25 + moveBoost * 0.25));
    }

    private void tickLightning(World world, double moveDistance) {
        double intensity = computeLightningIntensity(moveDistance);
        long now = System.currentTimeMillis();
        long interval = (long) (lightningMaxIntervalMs
                - (lightningMaxIntervalMs - lightningMinIntervalMs) * intensity);
        if (now - lastLightningMs < interval) {
            return;
        }
        if (random.nextDouble() > 0.45 + intensity * 0.55) {
            return;
        }

        lastLightningMs = now;
        int bolts = 1 + (intensity > 0.6 && random.nextDouble() < 0.5 ? 1 : 0);
        for (int i = 0; i < bolts; i++) {
            strikeBolt(world, intensity);
        }
    }

    private void strikeBolt(World world, double intensity) {
        // Strike at ground level around the tornado's edge: bolts spawned inside
        // the particle column get visually swallowed by the flames and smoke.
        double angle = random.nextDouble() * Math.PI * 2;
        double dist = horizontalRadius * (0.4 + random.nextDouble() * 0.9);
        double sx = location.getX() + Math.cos(angle) * dist;
        double sz = location.getZ() + Math.sin(angle) * dist;
        int groundY = findSurfaceY(world, (int) Math.floor(sx), (int) Math.floor(sz));
        Location strikeLoc = new Location(world, sx, groundY + 1, sz);

        int sparkCount = 4 + (int) (intensity * 18);
        spawnVisibleParticle(world, Particle.ELECTRIC_SPARK, sx, groundY + 1.5, sz,
                sparkCount, 0.35, 0.6, 0.35, 0.04);
        spawnVisibleParticle(world, Particle.FLASH, sx, groundY + 1.5, sz, 1, 0, 0, 0, 0);

        float thunderVolume = 0.35f + (float) intensity * 1.1f;
        world.playSound(strikeLoc, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, thunderVolume, 0.75f + (float) intensity * 0.35f);
        world.playSound(strikeLoc, Sound.ENTITY_LIGHTNING_BOLT_IMPACT, 0.5f + (float) intensity * 0.6f, 1.0f);

        if (intensity >= lightningRealStrikeThreshold) {
            world.strikeLightning(strikeLoc);
        } else {
            world.strikeLightningEffect(strikeLoc);
        }

        damageEntitiesAtCenter(world, strikeLoc, intensity);
    }

    private void damageEntitiesAtCenter(World world, Location center, double intensity) {
        double strikeRadius = 1.5 + intensity * 2.5;
        for (Entity entity : world.getNearbyEntities(center, strikeRadius, columnHeight * 0.4, strikeRadius)) {
            if (!(entity instanceof LivingEntity living) || entity.equals(anchor)) {
                continue;
            }
            if (entity instanceof Player player && !game.isParticipant(player.getUniqueId())) {
                continue;
            }
            living.damage(lightningDamage * intensity);
            living.setFireTicks(Math.max(living.getFireTicks(), (int) (40 + intensity * 60)));
        }
    }

    private void alignToSurface(World world) {
        // Follow the actual terrain (including the cone's slope) instead of
        // clamping to the volcano peak, easing toward it so the tornado glides
        // down the mountainside rather than hovering and then dropping.
        int groundY = findSurfaceY(world, location.getBlockX(), location.getBlockZ());
        double targetY = groundY + 1.5;
        double currentY = location.getY();
        double maxStep = 1.5;
        if (Math.abs(targetY - currentY) <= maxStep) {
            location.setY(targetY);
        } else {
            location.setY(currentY + Math.signum(targetY - currentY) * maxStep);
        }
    }

    private void spawnParticles(World world) {
        double baseX = location.getX();
        double baseY = location.getY();
        double baseZ = location.getZ();

        for (int h = 0; h < columnHeight; h++) {
            double heightFactor = h / (double) columnHeight;
            double ringRadius = horizontalRadius * (1.0 - heightFactor * 0.35);
            double angle = spinAngle + h * 0.65;
            double px = baseX + Math.cos(angle) * ringRadius;
            double pz = baseZ + Math.sin(angle) * ringRadius;
            double py = baseY + h * 0.55;

            spawnVisibleParticle(world, Particle.FLAME, px, py, pz, 10, 0.2, 0.3, 0.2, 0.03);
            if (h % 2 == 0) {
                spawnVisibleParticle(world, Particle.LARGE_SMOKE, px, py, pz, 6, 0.25, 0.4, 0.25, 0.02);
            }
            if (h % 3 == 0) {
                spawnVisibleParticle(world, Particle.CAMPFIRE_COSY_SMOKE, px, py + 0.5, pz, 3, 0.15, 0.35, 0.15, 0.01);
            }
            if (h > columnHeight * 0.55) {
                spawnVisibleParticle(world, Particle.SOUL_FIRE_FLAME, px, py, pz, 5, 0.15, 0.25, 0.15, 0.02);
            }
        }

        spawnVisibleParticle(world, Particle.LAVA, baseX, baseY, baseZ, 12, 0.6, 0.4, 0.6, 0.03);
        spawnVisibleParticle(world, Particle.FLAME, baseX, baseY, baseZ, 30, 0.8, 1.2, 0.8, 0.05);
    }

    private void spawnVisibleParticle(World world, Particle particle, double x, double y, double z,
                                      int count, double ox, double oy, double oz, double speed) {
        Object data = ParticleUtil.resolveData(particle);
        for (Player player : game.getAllParticipants()) {
            if (!player.getWorld().equals(world)) {
                continue;
            }
            if (particle.getDataType() == Void.class) {
                player.spawnParticle(particle, x, y, z, count, ox, oy, oz, speed, null, true);
            } else {
                player.spawnParticle(particle, x, y, z, count, ox, oy, oz, speed, data, true);
            }
        }
        ParticleUtil.spawn(world, particle, x, y, z, count, ox, oy, oz, speed);
    }

    /**
     * Ignites trees, accelerates burning logs/leaves, and vaporizes solid blocks above
     * local ground level inside the tornado column.
     */
    private void tickBlockEffects(World world) {
        if (blockDestroyChancePerTick <= 0 && burnChancePerTick <= 0) {
            return;
        }
        forEachBlockInColumn(world, block -> {
            int groundY = findSurfaceY(world, block.getX(), block.getZ());
            if (block.getY() <= groundY) {
                return;
            }

            Material type = block.getType();
            if (type.isAir() || type == Material.FIRE || type == Material.SOUL_FIRE) {
                return;
            }
            if (isProtectedFromTornado(type)) {
                return;
            }

            if (VolcanoHeatUtil.isFlammableTreeBlock(type)) {
                if (!VolcanoHeatUtil.hasAdjacentFire(block)) {
                    VolcanoHeatUtil.tryIgnite(block);
                    return;
                }
                if (burnChancePerTick > 0 && random.nextDouble() <= burnChancePerTick) {
                    VolcanoHeatUtil.burnFlammableBlock(block);
                }
                return;
            }

            if (blockDestroyChancePerTick <= 0 || random.nextDouble() > blockDestroyChancePerTick) {
                return;
            }
            destroyBlockWithEmbers(block);
        });
    }

    private void forEachBlockInColumn(World world, Consumer<Block> action) {
        int cx = location.getBlockX();
        int cy = location.getBlockY();
        int cz = location.getBlockZ();
        int r = (int) Math.ceil(horizontalRadius);
        int scanHeight = Math.min(columnHeight, 20);

        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (dx * dx + dz * dz > r * r) {
                    continue;
                }
                for (int dy = 0; dy < scanHeight; dy++) {
                    action.accept(world.getBlockAt(cx + dx, cy + dy, cz + dz));
                }
            }
        }
    }

    private static boolean isProtectedFromTornado(Material type) {
        return type == Material.BEDROCK
                || type == Material.BARRIER
                || type == Material.CHEST
                || type == Material.BARREL
                || type == Material.TRAPPED_CHEST
                || type == Material.ENDER_CHEST
                || type == Material.GOLD_BLOCK
                || type == Material.OBSIDIAN
                || type == Material.LAVA
                || type == Material.WATER;
    }

    private void destroyBlockWithEmbers(Block block) {
        for (BlockFace face : EMBER_FACES) {
            VolcanoHeatUtil.tryPlaceSustainedFire(block.getRelative(face));
        }
        block.setType(Material.AIR, false);
    }

    private void affectEntities(World world) {
        double pullRadius = horizontalRadius + 3.0;
        double liftRadius = horizontalRadius * 0.7;
        long now = System.currentTimeMillis();
        boolean shouldDamage = now - lastDamageMs >= damageIntervalMs;

        for (Entity entity : world.getNearbyEntities(location, pullRadius, columnHeight * 0.55, pullRadius)) {
            if (entity instanceof ArmorStand stand && ArenaEntityUtil.isPowerUpMarker(stand)) {
                Vector offset = entity.getLocation().toVector().subtract(location.toVector());
                offset.setY(0);
                double dist = offset.length();
                if (dist <= horizontalRadius + 1.5) {
                    plugin.getPowerUpManager().destroyByMarker(stand);
                }
                continue;
            }
            if (!(entity instanceof LivingEntity living) || entity.equals(anchor)) {
                continue;
            }
            if (entity instanceof Player player && !game.isParticipant(player.getUniqueId())) {
                continue;
            }

            Vector offset = entity.getLocation().toVector().subtract(location.toVector());
            offset.setY(0);
            double dist = offset.length();
            if (dist < 0.1) {
                offset = new Vector(random.nextDouble() - 0.5, 0, random.nextDouble() - 0.5);
                dist = offset.length();
            }
            if (dist > pullRadius) {
                continue;
            }

            Vector tangent = new Vector(-offset.getZ(), 0, offset.getX()).normalize();
            double spinStrength = 0.5 + (1.0 - dist / pullRadius) * 0.9;
            Vector velocity = tangent.multiply(spinStrength);

            if (dist < liftRadius) {
                double lift = 0.3 + (1.0 - dist / liftRadius) * 0.75;
                velocity.setY(lift);
            } else {
                velocity.setY(entity.getVelocity().getY() * 0.6 + 0.1);
            }

            Vector inward = offset.clone().normalize().multiply(-0.15);
            velocity.add(inward);
            entity.setVelocity(velocity);

            if (shouldDamage && dist <= horizontalRadius + 1.5) {
                living.setFireTicks(Math.max(living.getFireTicks(), 80));
                living.damage(damage);
            }
        }

        if (shouldDamage) {
            lastDamageMs = now;
        }
    }

    private int findSurfaceY(World world, int x, int z) {
        return VolcanoHeatUtil.findTerrainGroundY(world, x, z, baseSurfaceY);
    }
}
