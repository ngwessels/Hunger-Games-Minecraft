package com.hungergames.game;

import com.hungergames.HungerGamesPlugin;
import com.hungergames.arena.Arena;
import com.hungergames.arena.SettlementLayout;
import com.hungergames.gamemode.RageConfig;
import com.hungergames.util.ArenaEntityUtil;
import com.hungergames.util.CircleUtil;
import com.hungergames.util.ColorUtil;
import com.hungergames.util.ParticleUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Entity;
import org.bukkit.entity.IronGolem;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Village and creeper rage systems. Attacking a village villager summons
 * super-charged iron golems that hunt the attacker while they stay near a
 * village; attacking an ambient creeper triggers a 10-second charged-creeper
 * bombardment that ends in one synchronized detonation.
 */
public class RageManager {

    private record GolemHunt(List<UUID> golemIds) {
    }

    private record CreeperRage(long endMillis, List<UUID> creeperIds, long lastWaveMillis) {
    }

    private final HungerGamesPlugin plugin;
    private final Random random = new Random();

    private Game game;
    private Arena arena;

    private final Map<UUID, GolemHunt> golemRages = new HashMap<>();
    private final Map<UUID, CreeperRage> creeperRages = new HashMap<>();

    public RageManager(HungerGamesPlugin plugin) {
        this.plugin = plugin;
    }

    /** Called from tickActive (1 Hz). */
    public void tick(Game game) {
        this.game = game;
        this.arena = game.getArena();
        if (game.getState() != GameState.ACTIVE && game.getState() != GameState.GRACE) {
            return;
        }
        World world = arena.getWorld();
        if (world == null) {
            return;
        }

        RageConfig config = rageConfig(game);
        tickGolemRages(game, world, config);
        if (plugin.getTerrainPhaseManager().allowsHostileSpawning(game)) {
            tickCreeperAmbientSpawn(game, world, config);
        }
        tickCreeperRages(game, world, config);
    }

    public void clear(Game game) {
        World world = game.getArena().getWorld();
        if (world != null) {
            for (UUID id : collectTrackedIds()) {
                Entity entity = Bukkit.getEntity(id);
                if (entity instanceof LivingEntity living && !living.isDead()
                        && living.getWorld().equals(world)) {
                    living.remove();
                }
            }
        }
        golemRages.clear();
        creeperRages.clear();
        game = null;
        arena = null;
    }

    // ------------------------------------------------------------------
    // Iron golem rage
    // ------------------------------------------------------------------

    /**
     * Called when a player damages a village villager. Summons charged golems
     * at the nearest village that pursue the attacker while they stay close to
     * any village.
     */
    public void triggerGolemRage(Player attacker) {
        if (game == null || arena == null || !game.getAlivePlayers().contains(attacker)) {
            return;
        }
        RageConfig config = rageConfig(game);
        if (!config.isGolemRageEnabled()) {
            return;
        }
        World world = arena.getWorld();
        if (world == null || findNearestVillageSite(attacker.getLocation()) == null) {
            return;
        }
        if (golemRages.containsKey(attacker.getUniqueId())) {
            refreshGolems(attacker, config);
            return;
        }
        startGolemHunt(attacker, config);
    }

    private void startGolemHunt(Player target, RageConfig config) {
        World world = arena.getWorld();
        SettlementLayout.StructureSite site = findNearestVillageSite(target.getLocation());
        if (world == null || site == null) {
            return;
        }

        Location anchor = new Location(world, site.x() + 0.5, site.y(), site.z() + 0.5);
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < config.getGolemRageCount(); i++) {
            Location spot = resolveGroundNear(anchor, 3, 10);
            if (spot == null) {
                continue;
            }
            IronGolem golem = spawnChargedGolem(spot, config);
            if (golem != null) {
                golem.setTarget(target);
                ids.add(golem.getUniqueId());
            }
        }
        if (ids.isEmpty()) {
            return;
        }
        golemRages.put(target.getUniqueId(), new GolemHunt(ids));

        announce(target,
                "&4&lThe village rises against you! &7Its guardians will not relent while you remain.",
                "GOLEM RAGE",
                "The village remembers. Leave... or fall.");
        world.playSound(anchor, Sound.ENTITY_IRON_GOLEM_DAMAGE, 3f, 0.7f);
    }

    private void refreshGolems(Player attacker, RageConfig config) {
        GolemHunt hunt = golemRages.get(attacker.getUniqueId());
        if (hunt == null) {
            return;
        }
        World world = arena.getWorld();
        int alive = 0;
        for (UUID id : hunt.golemIds()) {
            if (Bukkit.getEntity(id) instanceof IronGolem golem && !golem.isDead()) {
                golem.setTarget(attacker);
                alive++;
            }
        }
        if (alive < Math.max(1, config.getGolemRageCount() / 2)) {
            SettlementLayout.StructureSite site = findNearestVillageSite(attacker.getLocation());
            if (site != null) {
                Location anchor = new Location(world, site.x() + 0.5, site.y(), site.z() + 0.5);
                Location spot = resolveGroundNear(anchor, 3, 10);
                if (spot != null) {
                    IronGolem golem = spawnChargedGolem(spot, config);
                    if (golem != null) {
                        golem.setTarget(attacker);
                        hunt.golemIds().add(golem.getUniqueId());
                    }
                }
            }
        }
    }

    private void tickGolemRages(Game game, World world, RageConfig config) {
        if (golemRages.isEmpty()) {
            return;
        }
        double villageRadiusSq = config.getGolemRageVillageRadius()
                * config.getGolemRageVillageRadius();
        List<UUID> ended = new ArrayList<>();
        for (Map.Entry<UUID, GolemHunt> entry : golemRages.entrySet()) {
            Player victim = plugin.getServer().getPlayer(entry.getKey());
            boolean gone = victim == null || !victim.isOnline()
                    || !game.getAlivePlayers().contains(victim)
                    || !victim.getWorld().equals(world);
            if (gone) {
                endGolemHunt(entry.getValue());
                ended.add(entry.getKey());
                continue;
            }

            SettlementLayout.StructureSite nearest =
                    findNearestVillageSite(victim.getLocation());
            boolean nearVillage = nearest != null
                    && distanceSq(nearest.x(), nearest.z(),
                            victim.getLocation().getX(), victim.getLocation().getZ())
                            <= villageRadiusSq;
            if (!nearVillage) {
                endGolemHunt(entry.getValue());
                ended.add(entry.getKey());
                victim.sendMessage(plugin.formatMessage(
                        "&7You escaped the village. Its guardians return to slumber."));
                victim.playSound(victim.getLocation(), Sound.ENTITY_IRON_GOLEM_DAMAGE, 1f, 0.8f);
                continue;
            }
            retargetGolems(entry.getValue(), victim);
        }
        for (UUID id : ended) {
            golemRages.remove(id);
        }
    }

    private void retargetGolems(GolemHunt hunt, Player victim) {
        for (UUID id : hunt.golemIds()) {
            if (Bukkit.getEntity(id) instanceof Mob golem && !golem.isDead()) {
                LivingEntity current = golem.getTarget();
                if (current == null || !current.equals(victim)) {
                    golem.setTarget(victim);
                }
            }
        }
    }

    private void endGolemHunt(GolemHunt hunt) {
        World world = arena.getWorld();
        if (world == null) {
            return;
        }
        for (UUID id : hunt.golemIds()) {
            Entity entity = Bukkit.getEntity(id);
            if (entity instanceof LivingEntity living && !living.isDead()
                    && living.getWorld().equals(world)) {
                if (living instanceof Mob mob) {
                    mob.setTarget(null);
                }
                ParticleUtil.spawn(world, Particle.POOF,
                        living.getLocation(), 12, 0.4, 0.8, 0.4, 0.02);
                living.remove();
            }
        }
    }

    private IronGolem spawnChargedGolem(Location location, RageConfig config) {
        return location.getWorld().spawn(location, IronGolem.class, entity -> {
            entity.setPlayerCreated(false);
            entity.setRemoveWhenFarAway(false);
            ArenaEntityUtil.tagRageGolem(plugin, entity);
            applyRageBuffs(entity, config.getGolemRageHealthBonus(),
                    config.getGolemRageDamageBonus(), config.getGolemRageSpeedBonus());
            entity.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING,
                    PotionEffect.INFINITE_DURATION, 0, true, false));
        });
    }

    private void applyRageBuffs(Mob mob, double healthBonus, double damageBonus, double speedBonus) {
        if (ArenaEntityUtil.isRageUnitBuffed(mob)) {
            return;
        }
        ArenaEntityUtil.markRageUnitBuffed(mob);
        AttributeInstance health = mob.getAttribute(Attribute.MAX_HEALTH);
        if (health != null) {
            health.setBaseValue(health.getBaseValue() + healthBonus);
            mob.setHealth(health.getValue());
        }
        AttributeInstance damage = mob.getAttribute(Attribute.ATTACK_DAMAGE);
        if (damage != null) {
            damage.setBaseValue(damage.getBaseValue() + damageBonus);
        }
        AttributeInstance speed = mob.getAttribute(Attribute.MOVEMENT_SPEED);
        if (speed != null) {
            speed.setBaseValue(speed.getBaseValue() * (1.0 + speedBonus));
        }
    }

    private SettlementLayout.StructureSite findNearestVillageSite(Location from) {
        if (arena == null) {
            return null;
        }
        SettlementLayout.StructureSite best = null;
        double bestSq = Double.MAX_VALUE;
        for (SettlementLayout.StructureSite site : arena.getSettlementLayout().getMobSites()) {
            if (site.kind() != SettlementLayout.StructureSite.Kind.VILLAGE) {
                continue;
            }
            double distSq = distanceSq(site.x(), site.z(), from.getX(), from.getZ());
            if (distSq < bestSq) {
                bestSq = distSq;
                best = site;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------
    // Creepers: ambient population + rage
    // ------------------------------------------------------------------

    /** Called by GameListener when a player damages a tagged ambient creeper. */
    public void triggerCreeperRage(Player attacker) {
        if (game == null || arena == null || !game.getAlivePlayers().contains(attacker)) {
            return;
        }
        RageConfig config = rageConfig(game);
        if (!config.isCreeperRageEnabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        CreeperRage existing = creeperRages.get(attacker.getUniqueId());
        if (existing != null && now < existing.endMillis()) {
            // Already mid-bombardment; no repeat alerts.
            return;
        }

        long endMillis = now + config.getCreeperRageDurationSeconds() * 1000L;
        List<UUID> ids = existing != null ? existing.creeperIds() : new ArrayList<>();
        creeperRages.put(attacker.getUniqueId(), new CreeperRage(endMillis, ids, 0L));

        announce(attacker,
                "&2&lThe hiss spreads! &7Charged creepers swarm you for "
                        + config.getCreeperRageDurationSeconds() + " seconds!",
                "CREEPER RAGE",
                "They will all blow at once.");
        attacker.playSound(attacker.getLocation(), Sound.ENTITY_CREEPER_PRIMED, 1.5f, 0.6f);
    }

    private void tickCreeperAmbientSpawn(Game game, World world, RageConfig config) {
        if (config.getCreeperAmbientMax() <= 0
                || random.nextDouble() > config.getCreeperAmbientSpawnChancePerTick()) {
            return;
        }
        if (countVolcanoCreepers(world) >= config.getCreeperAmbientMax()) {
            return;
        }
        Location spawn = findSurfaceSpot(world,
                arena.getCenterX(), arena.getCenterZ(), (int) arena.getCenterY(),
                config.getCreeperAmbientRadiusMin(), config.getCreeperAmbientRadiusMax());
        if (spawn != null) {
            spawnAmbientCreeper(spawn);
        }
    }

    private void tickCreeperRages(Game game, World world, RageConfig config) {
        if (creeperRages.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        List<UUID> finished = new ArrayList<>();
        for (Map.Entry<UUID, CreeperRage> entry : creeperRages.entrySet()) {
            CreeperRage rage = entry.getValue();
            Player victim = plugin.getServer().getPlayer(entry.getKey());

            boolean gone = victim == null || !victim.isOnline()
                    || !game.getAlivePlayers().contains(victim)
                    || !victim.getWorld().equals(world);
            if (gone) {
                removeRageCreepersQuietly(rage.creeperIds(), world);
                finished.add(entry.getKey());
                continue;
            }

            if (now >= rage.endMillis()) {
                detonateAll(rage.creeperIds(), world);
                finished.add(entry.getKey());
                continue;
            }

            long intervalMs = config.getCreeperRageSpawnIntervalTicks() * 50L;
            if (now - rage.lastWaveMillis() >= intervalMs) {
                List<UUID> spawned = spawnWave(victim, world, config);
                rage.creeperIds().addAll(spawned);
                creeperRages.put(entry.getKey(), new CreeperRage(
                        rage.endMillis(), rage.creeperIds(), now));
            }
        }
        for (UUID id : finished) {
            creeperRages.remove(id);
        }
    }

    private List<UUID> spawnWave(Player victim, World world, RageConfig config) {
        List<UUID> spawned = new ArrayList<>();
        for (int i = 0; i < config.getCreeperRagePerWave(); i++) {
            Location spot = findSurfaceSpot(world,
                    victim.getLocation().getX(), victim.getLocation().getZ(),
                    victim.getLocation().getBlockY(), 3, 8);
            if (spot == null) {
                continue;
            }
            Creeper creeper = world.spawn(spot, Creeper.class, entity -> {
                entity.setPowered(true);
                entity.setMaxFuseTicks(config.getCreeperFuseTicks());
                entity.setRemoveWhenFarAway(true);
                entity.setTarget(victim);
                ArenaEntityUtil.tagRageCreeper(plugin, entity);
            });
            spawned.add(creeper.getUniqueId());
            world.playSound(spot, Sound.ENTITY_CREEPER_PRIMED, 1f, 0.75f);
        }
        return spawned;
    }

    /** The synchronized finale: every surviving rage creeper blows at once. */
    private void detonateAll(List<UUID> creeperIds, World world) {
        for (UUID id : creeperIds) {
            if (Bukkit.getEntity(id) instanceof Creeper creeper && !creeper.isDead()
                    && creeper.getWorld().equals(world)) {
                creeper.explode();
            }
        }
        creeperIds.clear();
    }

    private void removeRageCreepersQuietly(List<UUID> creeperIds, World world) {
        for (UUID id : creeperIds) {
            Entity entity = Bukkit.getEntity(id);
            if (entity instanceof LivingEntity living && !living.isDead()
                    && living.getWorld().equals(world)) {
                living.remove();
            }
        }
        creeperIds.clear();
    }

    private void spawnAmbientCreeper(Location location) {
        location.getWorld().spawn(location, Creeper.class, entity -> {
            entity.setRemoveWhenFarAway(true);
            ArenaEntityUtil.tagVolcanoCreeper(plugin, entity);
        });
    }

    // ------------------------------------------------------------------
    // Shared helpers
    // ------------------------------------------------------------------

    private void announce(Player player, String chatMessage, String title, String subtitle) {
        player.sendMessage(plugin.formatMessage(chatMessage));
        player.sendTitle(
                ColorUtil.color("&4&l" + title),
                ColorUtil.color("&7" + subtitle),
                10, 70, 20);
    }

    private RageConfig rageConfig(Game game) {
        RageConfig config = game.getGameMode().getRage();
        return config != null ? config : new RageConfig();
    }

    private List<UUID> collectTrackedIds() {
        Set<UUID> ids = new HashSet<>();
        for (GolemHunt hunt : golemRages.values()) {
            ids.addAll(hunt.golemIds());
        }
        for (CreeperRage rage : creeperRages.values()) {
            ids.addAll(rage.creeperIds());
        }
        return new ArrayList<>(ids);
    }

    private int countVolcanoCreepers(World world) {
        int count = 0;
        for (Entity entity : world.getEntitiesByClass(Creeper.class)) {
            if (entity instanceof Creeper creeper && ArenaEntityUtil.isVolcanoCreeper(creeper)) {
                count++;
            }
        }
        return count;
    }

    private double distanceSq(double ax, double az, double bx, double bz) {
        double dx = ax - bx;
        double dz = az - bz;
        return dx * dx + dz * dz;
    }

    /** Surface spot within [min,max] radius of the given point; null when none found. */
    private Location findSurfaceSpot(World world, double centerX, double centerZ,
                                     int baseY, int minRadius, int maxRadius) {
        for (int attempt = 0; attempt < 14; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double dist = minRadius + random.nextDouble() * Math.max(1, maxRadius - minRadius);
            int x = (int) Math.round(centerX + Math.cos(angle) * dist);
            int z = (int) Math.round(centerZ + Math.sin(angle) * dist);
            if (!isInsideArena(x, z)) {
                continue;
            }
            if (!plugin.getTerrainPhaseManager().isVolcanoForestAt(world, x, z)) {
                continue;
            }
            int y = findSurfaceY(world, x, baseY, z);
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

    private Location resolveGroundNear(Location base, int minOffset, int maxOffset) {
        World world = base.getWorld();
        if (world == null) {
            return null;
        }
        for (int attempt = 0; attempt < 16; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double dist = minOffset + random.nextDouble() * Math.max(1, maxOffset - minOffset);
            int x = base.getBlockX() + (int) Math.round(Math.cos(angle) * dist);
            int z = base.getBlockZ() + (int) Math.round(Math.sin(angle) * dist);
            if (!isInsideArena(x, z)) {
                continue;
            }
            if (!plugin.getTerrainPhaseManager().isVolcanoForestAt(world, x, z)) {
                continue;
            }
            for (int dy = -4; dy <= 5; dy++) {
                int y = base.getBlockY() + dy;
                Block below = world.getBlockAt(x, y - 1, z);
                Block feet = world.getBlockAt(x, y, z);
                Block head = world.getBlockAt(x, y + 1, z);
                Block aboveHead = world.getBlockAt(x, y + 2, z);
                if (below.getType().isSolid() && !below.isLiquid()
                        && feet.getType().isAir() && head.getType().isAir()
                        && aboveHead.getType().isAir()) {
                    return new Location(world, x + 0.5, y, z + 0.5);
                }
            }
        }
        return null;
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
}
