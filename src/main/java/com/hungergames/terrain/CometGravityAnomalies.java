package com.hungergames.terrain;

import com.hungergames.HungerGamesPlugin;
import com.hungergames.arena.Arena;
import com.hungergames.game.Game;
import com.hungergames.game.GameState;
import com.hungergames.game.PlayerSession;
import com.hungergames.gamemode.CometConfig;
import com.hungergames.util.ColorUtil;
import com.hungergames.util.ParticleUtil;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Patchy comet gravity: random pockets of low-g, heavy-g, or drift. Only
 * players standing in a pocket are affected.
 */
public class CometGravityAnomalies {

    private static final UUID GRAVITY_ID = UUID.nameUUIDFromBytes("hg_comet_gravity".getBytes());
    private static final UUID JUMP_ID = UUID.nameUUIDFromBytes("hg_comet_jump".getBytes());
    private static final UUID FALL_ID = UUID.nameUUIDFromBytes("hg_comet_fall".getBytes());
    private static final UUID SAFE_FALL_ID = UUID.nameUUIDFromBytes("hg_comet_safe_fall".getBytes());

    private enum Kind {
        LIGHT, HEAVY, DRIFT
    }

    private record Zone(double x, double z, double radius, Kind kind,
                        double gravityAmount, double jumpAmount, long expireAt) {
        boolean contains(double px, double pz) {
            double dx = px - x;
            double dz = pz - z;
            return dx * dx + dz * dz <= radius * radius;
        }
    }

    private final List<Zone> zones = new ArrayList<>();
    private final Map<UUID, Kind> lastKindByPlayer = new HashMap<>();
    private int tick;
    private int spawnCooldown;

    public void reset() {
        zones.clear();
        lastKindByPlayer.clear();
        tick = 0;
        spawnCooldown = 40;
    }

    public void tick(HungerGamesPlugin plugin, Game game, Arena arena, CometConfig config, double cometProgress) {
        if (config == null || !config.isGravityEnabled()) {
            return;
        }
        World world = arena.getWorld();
        if (world == null) {
            return;
        }
        tick++;
        expireZones();
        if (tick % 20 == 0) {
            maybeSpawn(arena, config, cometProgress);
        }
        applyToPlayers(plugin, game, world);
        if (tick % 6 == 0) {
            drawZones(world, arena);
        }
    }

    public void clear(Game game) {
        if (game != null) {
            for (Player player : game.getAllParticipants()) {
                if (player != null && player.isOnline()) {
                    clearPlayer(player);
                }
            }
        }
        zones.clear();
        lastKindByPlayer.clear();
    }

    private void expireZones() {
        long now = System.currentTimeMillis();
        Iterator<Zone> it = zones.iterator();
        while (it.hasNext()) {
            if (it.next().expireAt() <= now) {
                it.remove();
            }
        }
        if (spawnCooldown > 0) {
            spawnCooldown--;
        }
    }

    private void maybeSpawn(Arena arena, CometConfig config, double cometProgress) {
        if (spawnCooldown > 0) {
            return;
        }
        int max = config.getGravityMaxZones();
        if (zones.size() >= max) {
            return;
        }
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double chance = config.getGravitySpawnChancePerSecond() * (0.65 + cometProgress * 0.9);
        if (random.nextDouble() > chance) {
            spawnCooldown = 20 + random.nextInt(40);
            return;
        }
        double cx = arena.getCenterX();
        double cz = arena.getCenterZ();
        int playable = Math.max(40, arena.getRadius() - 18);
        double sector = random.nextDouble() * Math.PI * 2;
        int toPlace = random.nextDouble() < 0.28 && zones.size() + 1 < max ? 2 : 1;
        for (int n = 0; n < toPlace; n++) {
            double angle = sector + (random.nextDouble() - 0.5) * 0.7;
            double dist = 36 + random.nextDouble() * Math.max(20, playable - 50);
            double x = cx + Math.cos(angle) * dist;
            double z = cz + Math.sin(angle) * dist;
            double radius = config.getGravityMinRadius()
                    + random.nextDouble() * Math.max(1.0,
                    config.getGravityMaxRadius() - config.getGravityMinRadius());
            if (tooClose(x, z, radius)) {
                continue;
            }
            Kind kind = rollKind(random);
            double[] strength = strengthFor(kind, random);
            int duration = config.getGravityMinDurationSeconds()
                    + random.nextInt(Math.max(1,
                    config.getGravityMaxDurationSeconds() - config.getGravityMinDurationSeconds() + 1));
            zones.add(new Zone(x, z, radius, kind, strength[0], strength[1],
                    System.currentTimeMillis() + duration * 1000L));
        }
        spawnCooldown = 80 + random.nextInt(100);
    }

    private boolean tooClose(double x, double z, double radius) {
        for (Zone zone : zones) {
            double dx = x - zone.x();
            double dz = z - zone.z();
            double need = radius + zone.radius() + 10;
            if (dx * dx + dz * dz < need * need) {
                return true;
            }
        }
        return false;
    }

    private static Kind rollKind(ThreadLocalRandom random) {
        double roll = random.nextDouble();
        if (roll < 0.45) {
            return Kind.LIGHT;
        }
        if (roll < 0.80) {
            return Kind.HEAVY;
        }
        return Kind.DRIFT;
    }

    /** {gravity multiply-scalar amount, jump add-number amount} */
    private static double[] strengthFor(Kind kind, ThreadLocalRandom random) {
        return switch (kind) {
            case LIGHT -> new double[]{
                    -0.48 - random.nextDouble() * 0.28,
                    0.16 + random.nextDouble() * 0.28
            };
            case HEAVY -> new double[]{
                    0.7 + random.nextDouble() * 0.9,
                    -0.14 - random.nextDouble() * 0.16
            };
            case DRIFT -> new double[]{
                    -0.78 - random.nextDouble() * 0.12,
                    0.08 + random.nextDouble() * 0.12
            };
        };
    }

    private void applyToPlayers(HungerGamesPlugin plugin, Game game, World world) {
        for (Player player : game.getAlivePlayers()) {
            if (player == null || !player.isOnline() || player.getWorld() != world) {
                continue;
            }
            PlayerSession session = game.getSession(player.getUniqueId());
            if (session != null && (session.isSpectator() || session.isInRespawnGrace())) {
                clearPlayer(player);
                lastKindByPlayer.remove(player.getUniqueId());
                continue;
            }
            if (game.getState() != GameState.GRACE && game.getState() != GameState.ACTIVE) {
                clearPlayer(player);
                lastKindByPlayer.remove(player.getUniqueId());
                continue;
            }
            Zone zone = zoneAt(player.getLocation().getX(), player.getLocation().getZ());
            if (zone == null) {
                if (lastKindByPlayer.remove(player.getUniqueId()) != null) {
                    clearPlayer(player);
                }
                continue;
            }
            applyZone(player, zone);
            Kind previous = lastKindByPlayer.put(player.getUniqueId(), zone.kind());
            if (previous != zone.kind()) {
                announce(plugin, player, zone);
            }
        }
    }

    private Zone zoneAt(double x, double z) {
        Zone best = null;
        double bestDist = Double.MAX_VALUE;
        for (Zone zone : zones) {
            if (!zone.contains(x, z)) {
                continue;
            }
            double dx = x - zone.x();
            double dz = z - zone.z();
            double dist = dx * dx + dz * dz;
            if (dist < bestDist) {
                bestDist = dist;
                best = zone;
            }
        }
        return best;
    }

    private void applyZone(Player player, Zone zone) {
        setModifier(player.getAttribute(Attribute.GRAVITY), GRAVITY_ID, "hg_comet_gravity",
                zone.gravityAmount(), AttributeModifier.Operation.MULTIPLY_SCALAR_1);
        setModifier(player.getAttribute(Attribute.JUMP_STRENGTH), JUMP_ID, "hg_comet_jump",
                zone.jumpAmount(), AttributeModifier.Operation.ADD_NUMBER);
        if (zone.kind() == Kind.HEAVY) {
            setModifier(player.getAttribute(Attribute.FALL_DAMAGE_MULTIPLIER), FALL_ID, "hg_comet_fall",
                    0.35, AttributeModifier.Operation.ADD_NUMBER);
            clearModifier(player.getAttribute(Attribute.SAFE_FALL_DISTANCE), SAFE_FALL_ID);
        } else {
            double extraSafe = zone.kind() == Kind.DRIFT ? 14.0 : 8.0;
            setModifier(player.getAttribute(Attribute.SAFE_FALL_DISTANCE), SAFE_FALL_ID, "hg_comet_safe_fall",
                    extraSafe, AttributeModifier.Operation.ADD_NUMBER);
            setModifier(player.getAttribute(Attribute.FALL_DAMAGE_MULTIPLIER), FALL_ID, "hg_comet_fall",
                    -0.45, AttributeModifier.Operation.ADD_NUMBER);
        }
        if (zone.kind() == Kind.DRIFT && player.getVelocity().getY() < -0.08) {
            Vector velocity = player.getVelocity();
            velocity.setY(velocity.getY() * 0.82);
            player.setVelocity(velocity);
        }
    }

    private void announce(HungerGamesPlugin plugin, Player player, Zone zone) {
        String message = switch (zone.kind()) {
            case LIGHT -> "&bLow gravity";
            case HEAVY -> "&cHeavy gravity";
            case DRIFT -> "&dWeightless drift";
        };
        player.spigot().sendMessage(
                net.md_5.bungee.api.ChatMessageType.ACTION_BAR,
                net.md_5.bungee.api.chat.TextComponent.fromLegacyText(ColorUtil.color(message)));
        Sound sound = zone.kind() == Kind.HEAVY ? Sound.ENTITY_IRON_GOLEM_ATTACK : Sound.ENTITY_ENDERMAN_TELEPORT;
        player.playSound(player.getLocation(), sound, 0.55f, zone.kind() == Kind.HEAVY ? 0.6f : 1.4f);
    }

    private void drawZones(World world, Arena arena) {
        int baseY = (int) arena.getCenterY() + 1;
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (Zone zone : zones) {
            Particle particle = switch (zone.kind()) {
                case LIGHT -> Particle.END_ROD;
                case HEAVY -> Particle.SMOKE;
                case DRIFT -> Particle.REVERSE_PORTAL;
            };
            int count = 8;
            for (int i = 0; i < count; i++) {
                double angle = (Math.PI * 2 * i) / count + tick * 0.04;
                double x = zone.x() + Math.cos(angle) * zone.radius();
                double z = zone.z() + Math.sin(angle) * zone.radius();
                double y = baseY + 1 + random.nextDouble() * 6;
                ParticleUtil.spawnForced(world, particle, x, y, z, 1, 0.12, 0.4, 0.12, 0.0);
            }
            for (int i = 0; i < 4; i++) {
                double ox = (random.nextDouble() - 0.5) * 2 * zone.radius();
                double oz = (random.nextDouble() - 0.5) * 2 * zone.radius();
                if (ox * ox + oz * oz > zone.radius() * zone.radius()) {
                    continue;
                }
                double y = baseY + random.nextDouble() * 8;
                Particle extra = zone.kind() == Kind.HEAVY ? Particle.ASH : Particle.CLOUD;
                ParticleUtil.spawnForced(world, extra, zone.x() + ox, y, zone.z() + oz,
                        1, 0.2, 0.3, 0.2, 0.0);
            }
        }
    }

    public void clearPlayer(Player player) {
        clearModifier(player.getAttribute(Attribute.GRAVITY), GRAVITY_ID);
        clearModifier(player.getAttribute(Attribute.JUMP_STRENGTH), JUMP_ID);
        clearModifier(player.getAttribute(Attribute.FALL_DAMAGE_MULTIPLIER), FALL_ID);
        clearModifier(player.getAttribute(Attribute.SAFE_FALL_DISTANCE), SAFE_FALL_ID);
    }

    private static void setModifier(AttributeInstance attribute, UUID id, String name,
                                    double amount, AttributeModifier.Operation operation) {
        if (attribute == null) {
            return;
        }
        clearModifier(attribute, id);
        attribute.addModifier(new AttributeModifier(id, name, amount, operation));
    }

    private static void clearModifier(AttributeInstance attribute, UUID id) {
        if (attribute == null) {
            return;
        }
        for (AttributeModifier modifier : List.copyOf(attribute.getModifiers())) {
            if (id.equals(modifier.getUniqueId())) {
                attribute.removeModifier(modifier);
            }
        }
    }
}
