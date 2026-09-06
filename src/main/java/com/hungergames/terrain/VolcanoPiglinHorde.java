package com.hungergames.terrain;

import com.hungergames.HungerGamesPlugin;
import com.hungergames.arena.Arena;
import com.hungergames.arena.VolcanoCaldera;
import com.hungergames.game.Game;
import com.hungergames.game.GameState;
import com.hungergames.gamemode.TerrainPhaseConfig;
import com.hungergames.util.ArenaEntityUtil;
import com.hungergames.util.CircleUtil;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Hoglin;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.PigZombie;
import org.bukkit.entity.PiglinBrute;
import org.bukkit.entity.Player;
import org.bukkit.entity.WitherSkeleton;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Volcano zombified piglin horde. Piglins wander the slopes once the eruption begins,
 * but attacking one triggers a timed swarm locked to that attacker: nearby pack
 * members and spawned reinforcements hunt only them, move faster than a sprinting
 * player, and the rage ends after a fixed duration (or on death).
 */
public class VolcanoPiglinHorde {

    private static final int MAX_ANGER = 400;
    private static final double REINFORCE_RADIUS_SQ = 24 * 24;
    private static final double PACK_CLAIM_RADIUS_SQ = 32 * 32;
    private static final double RANGER_MIN_RANGE_SQ = 6 * 6;
    private static final double RANGER_MAX_RANGE_SQ = 24 * 24;
    private static final double ARROW_DAMAGE = 3.0;
    /** Rangers aim this far above the victim's feet so pillaring stays dangerous. */
    private static final double ARROW_LEAD_Y = 0.8;

    private final HungerGamesPlugin plugin;
    private final Random random = new Random();

    private Game game;
    private Arena arena;
    private TerrainPhaseConfig config;
    private BukkitTask task;
    private boolean active;

    private double centerX;
    private double centerZ;
    private long spawnTimeMs;
    private boolean started;

    /** Players currently hunted, mapped to the millis when their rage expires. */
    private final Map<UUID, Long> huntEndMillis = new HashMap<>();
    /** Per-ranger next allowed shot time, so rangers fire on a cadence. */
    private final Map<UUID, Long> rangerCooldowns = new HashMap<>();

    private int ambientSpawnRadiusMin;
    private int ambientSpawnRadiusMax;
    private int netherSpreadRadius;
    private int maxAmbientPiglins;
    private double ambientSpawnChancePerTick;
    private int swarmSizePerAttack;
    private int maxSwarmPerVictim;
    private double swarmHealthBonus;
    private double swarmDamageBonus;
    private double swarmSpeedBonus;
    private int swarmSpeedAmplifier;
    private int rageDurationSeconds;
    private double aggroRangeDuringSwarm;
    private int swarmReinforceBudgetPerTick;
    private double rangerShare;
    private long rangerFireIntervalMs;
    private double rangerArrowSpeed;
    private double bruteShare;
    private double hoglinShare;
    private double witherSkeletonShare;

    public VolcanoPiglinHorde(HungerGamesPlugin plugin) {
        this.plugin = plugin;
    }

    public void configure(TerrainPhaseConfig config) {
        this.config = config;
        maxAmbientPiglins = config.getInt("piglinHordeMaxAmbient", 8);
        ambientSpawnChancePerTick = config.getDouble("piglinAmbientSpawnChancePerTick", 0.02);
        ambientSpawnRadiusMin = config.getInt("piglinSpawnRadiusMin", 12);
        ambientSpawnRadiusMax = config.getInt("piglinSpawnRadiusMax", 45);
        swarmSizePerAttack = Math.max(1, config.getInt("piglinSwarmSizePerAttack", 6));
        maxSwarmPerVictim = Math.max(swarmSizePerAttack, config.getInt("piglinSwarmMaxPerVictim", 14));
        swarmHealthBonus = config.getDouble("piglinSwarmHealthBonus", 10.0);
        swarmDamageBonus = config.getDouble("piglinSwarmDamageBonus", 4.0);
        swarmSpeedBonus = config.getDouble("piglinSwarmSpeedBonus", 0.55);
        swarmSpeedAmplifier = Math.max(0, config.getInt("piglinSwarmSpeedAmplifier", 1));
        rageDurationSeconds = Math.max(1, config.getInt("piglinRageDurationSeconds", 90));
        aggroRangeDuringSwarm = config.getDouble("piglinAggroRangeDuringSwarm", 96.0);
        swarmReinforceBudgetPerTick = Math.max(0, config.getInt("piglinSwarmReinforcePerTick", 1));
        rangerShare = Math.min(1.0, Math.max(0.0, config.getDouble("piglinRangerShare", 0.35)));
        rangerFireIntervalMs = config.getInt("piglinRangerFireIntervalTicks", 40) * 50L;
        rangerArrowSpeed = config.getDouble("piglinRangerArrowSpeed", 1.6);
        bruteShare = Math.min(1.0, Math.max(0.0, config.getDouble("piglinBruteShare", 0.15)));
        hoglinShare = Math.min(1.0, Math.max(0.0, config.getDouble("piglinHoglinShare", 0.15)));
        witherSkeletonShare = Math.min(1.0, Math.max(0.0, config.getDouble("piglinWitherSkeletonShare", 0.15)));
    }

    public void setNetherSpreadRadius(int radius) {
        this.netherSpreadRadius = Math.max(VolcanoCaldera.NO_TREE_RADIUS, radius);
    }

    public boolean isActive() {
        return active;
    }

    public void start(Game game, Arena arena) {
        if (active) {
            return;
        }
        World world = arena.getWorld();
        if (world == null) {
            return;
        }
        this.game = game;
        this.arena = arena;
        this.centerX = arena.getCenterX();
        this.centerZ = arena.getCenterZ();
        this.spawnTimeMs = System.currentTimeMillis();
        this.started = false;
        this.netherSpreadRadius = VolcanoCaldera.NO_TREE_RADIUS;
        this.huntEndMillis.clear();
        active = true;

        task = new BukkitRunnable() {
            @Override
            public void run() {
                if (!active || isGameOver()) {
                    destroy();
                    return;
                }
                tick();
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    public void destroy() {
        active = false;
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (Mob piglin : collectPiglins()) {
            if (ArenaEntityUtil.getVolcanoPiglinHuntOwner(piglin) != null) {
                piglin.remove();
            } else {
                calm(piglin);
            }
        }
        huntEndMillis.clear();
        rangerCooldowns.clear();
        game = null;
        arena = null;
    }

    private boolean isGameOver() {
        if (game == null || arena == null) {
            return true;
        }
        GameState state = game.getState();
        return state == GameState.ENDING || state == GameState.RESET || state == GameState.LOBBY;
    }

    /**
     * Called when a player damages a volcano piglin (melee or projectile).
     * Starts a timed hunt locked exclusively to that attacker.
     */
    public void handlePiglinAttacked(Player attacker, Mob attackedPiglin) {
        if (!active || game == null || !game.getAlivePlayers().contains(attacker)) {
            return;
        }

        UUID existingOwner = ArenaEntityUtil.getVolcanoPiglinHuntOwner(attackedPiglin);
        boolean ownedBySomeoneElse = existingOwner != null
                && !existingOwner.equals(attacker.getUniqueId());

        boolean firstOffense = !huntEndMillis.containsKey(attacker.getUniqueId());
        if (firstOffense) {
            huntEndMillis.put(attacker.getUniqueId(),
                    System.currentTimeMillis() + rageDurationSeconds * 1000L);
        }

        if (!ownedBySomeoneElse) {
            assignToHunt(attackedPiglin, attacker);
        }

        // Already being hunted: no repeated alerts, sounds, or bonus summons.
        if (!firstOffense) {
            return;
        }

        claimNearbyPack(attacker, attackedPiglin);

        World world = attackedPiglin.getWorld();
        world.playSound(attackedPiglin.getLocation(), Sound.ENTITY_ZOMBIFIED_PIGLIN_ANGRY, 2.5f, 0.7f);
        world.playSound(attackedPiglin.getLocation(), Sound.ENTITY_WITHER_SPAWN, 1f, 1.4f);

        attacker.sendMessage(plugin.formatMessage(
                "&4&lYou have enraged the volcano! &7The horde hunts you for "
                        + rageDurationSeconds + " seconds!"));
        attacker.sendTitle(
                ChatColor.DARK_RED + "" + ChatColor.BOLD + "PIGLIN RAGE",
                ChatColor.GRAY + "They will not chase anyone else. Survive "
                        + rageDurationSeconds + "s!",
                10, 70, 20);

        summonSwarm(attacker.getLocation(), attacker, swarmSizePerAttack);
    }

    /** Locks a unit onto one player for the rest of that hunt. */
    private void assignToHunt(Mob mob, Player owner) {
        ArenaEntityUtil.tagVolcanoPiglinHuntOwner(mob, owner.getUniqueId());
        mob.setRemoveWhenFarAway(false);
        setAnger(mob, MAX_ANGER);
        applySwarmBuffs(mob);
        mob.addPotionEffect(new PotionEffect(PotionEffectType.SPEED,
                PotionEffect.INFINITE_DURATION, swarmSpeedAmplifier, true, false));
        mob.setTarget(owner);
    }

    /**
     * The pack standing with the attacked piglin joins this hunt only.
     * Distant ambient units stay neutral so they cannot peel onto bystanders.
     */
    private void claimNearbyPack(Player attacker, Mob attackedPiglin) {
        Location origin = attackedPiglin.getLocation();
        for (Mob piglin : collectPiglins()) {
            if (piglin.equals(attackedPiglin)) {
                continue;
            }
            UUID owner = ArenaEntityUtil.getVolcanoPiglinHuntOwner(piglin);
            if (owner != null) {
                continue;
            }
            if (piglin.getLocation().distanceSquared(origin) <= PACK_CLAIM_RADIUS_SQ) {
                assignToHunt(piglin, attacker);
            }
        }
    }

    private void tick() {
        World world = game.getArena().getWorld();
        if (world == null) {
            destroy();
            return;
        }

        long elapsedSec = (System.currentTimeMillis() - spawnTimeMs) / 1000L;
        int startDelaySec = config != null ? config.getInt("piglinStartDelaySeconds", 15) : 15;
        if (!started) {
            if (elapsedSec >= startDelaySec) {
                started = true;
                announceArrival(world);
            }
            return;
        }

        tickHunt(world);
        tickRangers(world);
        tickAmbientSpawning(world);
    }

    /**
     * Ranger piglins shoot at hunted players who pillar up or keep distance,
     * so vertical escapes are no longer safe during a rage.
     */
    private void tickRangers(World world) {
        if (huntEndMillis.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();

        List<Player> hunted = new ArrayList<>();
        for (UUID targetId : huntEndMillis.keySet()) {
            Player victim = plugin.getServer().getPlayer(targetId);
            if (victim != null && victim.isOnline() && game.getAlivePlayers().contains(victim)) {
                hunted.add(victim);
            }
        }
        if (hunted.isEmpty()) {
            return;
        }

        List<Mob> piglins = collectPiglins();
        if (rangerCooldowns.size() > piglins.size() * 2) {
            Set<UUID> liveIds = new HashSet<>();
            for (Mob piglin : piglins) {
                liveIds.add(piglin.getUniqueId());
            }
            rangerCooldowns.keySet().removeIf(id -> !liveIds.contains(id));
        }
        for (Mob piglin : piglins) {
            var equipment = piglin.getEquipment();
            if (equipment == null
                    || equipment.getItemInMainHand().getType() != Material.CROSSBOW
                    || piglin.getTarget() == null || !piglin.getTarget().isValid()
                    || !(piglin.getTarget() instanceof Player currentVictim)
                    || !currentVictim.isOnline()) {
                continue;
            }
            Long nextShot = rangerCooldowns.get(piglin.getUniqueId());
            if (nextShot != null && now < nextShot) {
                continue;
            }

            Player target = currentVictim;
            double distSq = piglin.getLocation().distanceSquared(target.getLocation());
            if (distSq < RANGER_MIN_RANGE_SQ || distSq > RANGER_MAX_RANGE_SQ) {
                continue;
            }
            if (!piglin.hasLineOfSight(target)) {
                continue;
            }

            rangerCooldowns.put(piglin.getUniqueId(), now + rangerFireIntervalMs);
            fireArrow(piglin, target, world);
        }
    }

    private void fireArrow(Mob piglin, Player target, World world) {
        Location eye = piglin.getEyeLocation();
        Vector aim = target.getLocation().toVector()
                .add(new Vector(0, target.getHeight() * 0.6 + ARROW_LEAD_Y, 0))
                .subtract(eye.toVector());
        if (aim.lengthSquared() < 0.01) {
            return;
        }
        Vector velocity = aim.normalize().multiply(rangerArrowSpeed);

        Arrow arrow = world.spawn(eye, Arrow.class);
        arrow.setShooter(piglin);
        arrow.setVelocity(velocity);
        arrow.setDamage(ARROW_DAMAGE);
        arrow.setCritical(true);
        arrow.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
        world.playSound(eye, Sound.ITEM_CROSSBOW_SHOOT, 1f, 0.85f);
    }

    private void announceArrival(World world) {
        Location ventTop = new Location(world, centerX,
                world.getHighestBlockYAt((int) centerX, (int) centerZ) + 6, centerZ);
        world.playSound(ventTop, Sound.ENTITY_ZOMBIFIED_PIGLIN_AMBIENT, 3f, 0.5f);
        for (Player player : game.getAllParticipants()) {
            player.sendMessage(plugin.formatMessage(
                    "&6&lThe mountain stirs... &7Beasts and piglins crawl from the vents."));
        }
    }

    private void tickAmbientSpawning(World world) {
        if (countPiglins() >= maxAmbientPiglins) {
            return;
        }
        if (random.nextDouble() > ambientSpawnChancePerTick) {
            return;
        }
        Location spawn = findSurfaceSpawn(world);
        if (spawn != null) {
            spawnSwarmUnit(spawn, false);
        }
    }

    /**
     * Keeps every hunted player swarmed until the rage timer expires (or they die):
     * reinforces fallen pursuers near their victim and re-locks every owned unit
     * onto that player so vanilla AI cannot peel onto bystanders.
     */
    private void tickHunt(World world) {
        if (huntEndMillis.isEmpty()) {
            return;
        }

        long now = System.currentTimeMillis();
        List<Mob> piglins = collectPiglins();
        int reinforceBudget = swarmReinforceBudgetPerTick;
        List<UUID> endedHunts = new ArrayList<>();
        for (Map.Entry<UUID, Long> entry : huntEndMillis.entrySet()) {
            UUID targetId = entry.getKey();
            Player victim = plugin.getServer().getPlayer(targetId);
            boolean gone = victim == null || !victim.isOnline()
                    || !game.getAlivePlayers().contains(victim)
                    || arena == null || arena.getWorld() == null
                    || !victim.getWorld().equals(arena.getWorld());
            if (gone) {
                endedHunts.add(targetId);
                continue;
            }
            if (now >= entry.getValue()) {
                endedHunts.add(targetId);
                victim.sendMessage(plugin.formatMessage(
                        "&7The horde loses interest. You outlasted the rage."));
                victim.playSound(victim.getLocation(), Sound.ENTITY_ZOMBIFIED_PIGLIN_AMBIENT, 1.2f, 1.2f);
                continue;
            }

            Location victimLoc = victim.getLocation();
            int nearbySwarm = countNearbyOwnedSwarm(piglins, victimLoc, targetId);
            if (nearbySwarm < maxSwarmPerVictim && reinforceBudget > 0 && random.nextDouble() < 0.35) {
                Location drop = findSwarmDropLocation(world, victimLoc);
                if (drop != null) {
                    Mob reinforcement = spawnSwarmUnit(drop, true);
                    if (reinforcement != null) {
                        assignToHunt(reinforcement, victim);
                        reinforceBudget--;
                    }
                }
            }
        }
        for (UUID endedId : endedHunts) {
            endHunt(endedId);
        }

        lockHuntTargets(piglins);
    }

    /** Force every hunt unit back onto its owner; vanilla hoglin/brute AI will otherwise swap. */
    private void lockHuntTargets(List<Mob> piglins) {
        for (Mob piglin : piglins) {
            UUID ownerId = ArenaEntityUtil.getVolcanoPiglinHuntOwner(piglin);
            if (ownerId == null || !huntEndMillis.containsKey(ownerId)) {
                continue;
            }
            Player owner = plugin.getServer().getPlayer(ownerId);
            if (owner == null || !owner.isOnline()) {
                continue;
            }
            setAnger(piglin, MAX_ANGER);
            if (!(piglin.getTarget() instanceof Player player) || !player.getUniqueId().equals(ownerId)) {
                piglin.setTarget(owner);
            }
        }
    }

    /**
     * Called by GameListener when a hunted player dies; the horde loses interest
     * and despawns that player's pursuers.
     */
    public void handleTargetDeath(Player victim) {
        if (!active || !huntEndMillis.containsKey(victim.getUniqueId())) {
            return;
        }
        endHunt(victim.getUniqueId());
        victim.getWorld().playSound(victim.getLocation(), Sound.ENTITY_ZOMBIFIED_PIGLIN_DEATH, 2f, 0.8f);
    }

    private void endHunt(UUID targetId) {
        huntEndMillis.remove(targetId);
        despawnHuntMobs(targetId);
    }

    private void despawnHuntMobs(UUID targetId) {
        for (Mob piglin : collectPiglins()) {
            UUID owner = ArenaEntityUtil.getVolcanoPiglinHuntOwner(piglin);
            if (targetId.equals(owner)) {
                piglin.remove();
            }
        }
    }

    private int countNearbyOwnedSwarm(List<Mob> piglins, Location center, UUID ownerId) {
        int count = 0;
        for (Mob piglin : piglins) {
            UUID owner = ArenaEntityUtil.getVolcanoPiglinHuntOwner(piglin);
            if (!ownerId.equals(owner)) {
                continue;
            }
            if (piglin.getLocation().distanceSquared(center) <= REINFORCE_RADIUS_SQ) {
                count++;
            }
        }
        return count;
    }

    private Mob spawnSwarmUnit(Location location, boolean buffed) {
        Chunk chunk = location.getWorld().getChunkAt(location.getBlockX() >> 4, location.getBlockZ() >> 4);
        if (!chunk.isLoaded()) {
            chunk.load(true);
        }

        double roll = random.nextDouble();
        Mob unit;
        if (roll < hoglinShare) {
            unit = spawnHoglin(location);
        } else if (roll < hoglinShare + witherSkeletonShare) {
            unit = spawnWitherSkeleton(location);
        } else if (roll < hoglinShare + witherSkeletonShare + bruteShare) {
            unit = spawnPiglinBrute(location);
        } else {
            boolean ranger = random.nextDouble() < rangerShare;
            unit = spawnZombifiedPiglin(location, ranger);
        }
        ArenaEntityUtil.tagVolcanoPiglin(plugin, unit);
        if (buffed) {
            applySwarmBuffs(unit);
        }
        return unit;
    }

    private Hoglin spawnHoglin(Location location) {
        return location.getWorld().spawn(location, Hoglin.class, entity -> {
            // Overworld conversion would turn it into a zoglin mid-chase.
            entity.setImmuneToZombification(true);
            entity.setIsAbleToBeHunted(false);
            entity.setRemoveWhenFarAway(true);
            entity.setAdult();
        });
    }

    private PiglinBrute spawnPiglinBrute(Location location) {
        return location.getWorld().spawn(location, PiglinBrute.class, entity -> {
            entity.setImmuneToZombification(true);
            entity.setBaby(false);
            entity.setRemoveWhenFarAway(true);
        });
    }

    private WitherSkeleton spawnWitherSkeleton(Location location) {
        return location.getWorld().spawn(location, WitherSkeleton.class, entity -> {
            entity.setRemoveWhenFarAway(true);
            ItemStack sword = new ItemStack(Material.STONE_SWORD);
            var equipment = entity.getEquipment();
            if (equipment != null) {
                equipment.setItem(EquipmentSlot.HAND, sword);
                equipment.setItemInMainHandDropChance(0f);
            }
        });
    }

    private PigZombie spawnZombifiedPiglin(Location location, boolean ranger) {
        return location.getWorld().spawn(location, PigZombie.class, entity -> {
            entity.setBaby(false);
            entity.setRemoveWhenFarAway(true);
            if (ranger) {
                equipCrossbow(entity);
            }
        });
    }

    private void equipCrossbow(PigZombie piglin) {
        var equipment = piglin.getEquipment();
        if (equipment == null) {
            return;
        }
        ItemStack crossbow = new ItemStack(Material.CROSSBOW);
        equipment.setItem(EquipmentSlot.HAND, crossbow);
        // Volcano loot only; no free crossbows for the kill feed.
        equipment.setItemInMainHandDropChance(0f);
    }

    private void applySwarmBuffs(Mob piglin) {
        if (ArenaEntityUtil.isVolcanoPiglinBuffed(piglin)) {
            return;
        }
        ArenaEntityUtil.markVolcanoPiglinBuffed(piglin);
        AttributeInstance health = piglin.getAttribute(Attribute.MAX_HEALTH);
        if (health != null) {
            health.setBaseValue(health.getBaseValue() + swarmHealthBonus);
            piglin.setHealth(health.getValue());
        }
        AttributeInstance damage = piglin.getAttribute(Attribute.ATTACK_DAMAGE);
        if (damage != null) {
            damage.setBaseValue(damage.getBaseValue() + swarmDamageBonus);
        }
        AttributeInstance speed = piglin.getAttribute(Attribute.MOVEMENT_SPEED);
        if (speed != null) {
            speed.setBaseValue(speed.getBaseValue() * (1.0 + swarmSpeedBonus));
        }
        AttributeInstance follow = piglin.getAttribute(Attribute.FOLLOW_RANGE);
        if (follow != null) {
            follow.setBaseValue(Math.max(follow.getBaseValue(), aggroRangeDuringSwarm));
        }
    }

    private void calm(Mob piglin) {
        setAnger(piglin, 0);
        piglin.setTarget(null);
    }

    private void setAnger(Mob piglin, int anger) {
        if (piglin instanceof PigZombie zombie) {
            zombie.setAnger(anger);
            zombie.setAngry(anger > 0);
        }
    }

    private void summonSwarm(Location center, Player target, int size) {
        World world = center.getWorld();
        if (world == null) {
            return;
        }
        for (int i = 0; i < size; i++) {
            Location drop = findSwarmDropLocation(world, center);
            if (drop == null) {
                continue;
            }
            Mob unit = spawnSwarmUnit(drop, true);
            if (unit != null) {
                assignToHunt(unit, target);
            }
        }
        world.playSound(center, Sound.ENTITY_ZOMBIFIED_PIGLIN_ANGRY, 1.2f, 0.8f);
        world.playSound(center, Sound.ENTITY_HOGLIN_ANGRY, 1.2f, 0.9f);
    }

    /** Finds a valid surface spot a short hop from the victim for dropping pursuers. */
    private Location findSwarmDropLocation(World world, Location victimLoc) {
        for (int attempt = 0; attempt < 12; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double dist = 6 + random.nextDouble() * 14;
            int x = victimLoc.getBlockX() + (int) Math.round(Math.cos(angle) * dist);
            int z = victimLoc.getBlockZ() + (int) Math.round(Math.sin(angle) * dist);
            if (!isInsideArena(x, z)) {
                continue;
            }
            if (!isNetherSpawnColumn(world, x, z)) {
                continue;
            }
            int y = findSurfaceY(world, x, victimLoc.getBlockY(), z);
            Block floor = world.getBlockAt(x, y, z);
            Block feet = world.getBlockAt(x, y + 1, z);
            Block head = world.getBlockAt(x, y + 2, z);
            if (floor.getType().isSolid() && !floor.isLiquid()
                    && feet.getType().isAir() && head.getType().isAir()) {
                return new Location(world, x + 0.5, y + 1, z + 0.5);
            }
        }
        return null;
    }

    private Location findSurfaceSpawn(World world) {
        int maxRadius = Math.max(ambientSpawnRadiusMin + 1, netherSpreadRadius);
        for (int attempt = 0; attempt < 16; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double dist = ambientSpawnRadiusMin
                    + random.nextDouble() * Math.max(1, maxRadius - ambientSpawnRadiusMin);
            int x = (int) (centerX + Math.cos(angle) * dist);
            int z = (int) (centerZ + Math.sin(angle) * dist);
            if (!isInsideArena(x, z)) {
                continue;
            }
            if (!isNetherSpawnColumn(world, x, z)) {
                continue;
            }
            int y = findSurfaceY(world, x, (int) arena.getCenterY(), z);
            Block floor = world.getBlockAt(x, y, z);
            Block feet = world.getBlockAt(x, y + 1, z);
            if (floor.getType().isSolid() && !floor.isLiquid() && feet.getType().isAir()) {
                return new Location(world, x + 0.5, y + 1, z + 0.5);
            }
        }
        return null;
    }

    private boolean isNetherSpawnColumn(World world, int x, int z) {
        return plugin.getTerrainPhaseManager().isVolcanoNetherAt(world, x, z);
    }

    private boolean isInsideArena(int x, int z) {
        return arena == null || CircleUtil.isInsideCircle(
                arena.getCenterX(), arena.getCenterZ(), x, z, arena.getRadius() - 2);
    }

    private int findSurfaceY(World world, int x, int baseY, int z) {
        for (int y = baseY + 30; y >= 1; y--) {
            if (world.getBlockAt(x, y, z).getType().isSolid()) {
                return y;
            }
        }
        return baseY;
    }

    private List<Mob> collectPiglins() {
        List<Mob> result = new ArrayList<>();
        if (arena == null) {
            return result;
        }
        World world = arena.getWorld();
        if (world == null) {
            return result;
        }
        for (Entity entity : world.getEntitiesByClass(Mob.class)) {
            if (entity instanceof Mob mob && ArenaEntityUtil.isVolcanoPiglin(mob)) {
                result.add(mob);
            }
        }
        return result;
    }

    private int countPiglins() {
        return collectPiglins().size();
    }
}
