package com.hungergames.powerup;

import com.hungergames.HungerGamesPlugin;
import com.hungergames.arena.Arena;
import com.hungergames.game.Game;
import com.hungergames.game.GameState;
import com.hungergames.game.PlayerSession;
import com.hungergames.game.SavedPlayerState;
import com.hungergames.gamemode.TerrainPhaseConfig;
import com.hungergames.terrain.VolcanoGuardianBreath;
import com.hungergames.terrain.phases.VolcanoEruptionPhase;
import com.hungergames.util.ArenaEntityUtil;
import com.hungergames.util.ColorUtil;
import com.hungergames.util.EnderDragonMovement;
import com.hungergames.util.ParticleUtil;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class PlayerGuardianSession {

    private final HungerGamesPlugin plugin;
    private final Game game;
    private final Player player;
    private final PowerUpDefinition definition;
    private final VolcanoEruptionPhase volcanoPhase;
    private final TerrainPhaseConfig phaseConfig;
    private final SavedPlayerState savedState;

    private EnderDragon dragon;
    private BukkitTask task;
    private long endTimeMs;
    private long lastBreathMs;
    private final Set<UUID> highlightedBySession = new HashSet<>();
    private final double guardianScale;
    private final boolean invulnerable;
    private final long breathCooldownMs;
    private final double highlightRadius;

    public PlayerGuardianSession(
            HungerGamesPlugin plugin,
            Game game,
            Player player,
            PowerUpDefinition definition,
            VolcanoEruptionPhase volcanoPhase,
            TerrainPhaseConfig phaseConfig) {
        this.plugin = plugin;
        this.game = game;
        this.player = player;
        this.definition = definition;
        this.volcanoPhase = volcanoPhase;
        this.phaseConfig = phaseConfig;
        this.savedState = PlayerSession.capturePlayerState(player);
        this.guardianScale = Math.max(0.5, phaseConfig.getDouble("volcanoGuardianScale", 1.75));
        this.invulnerable = definition.getBoolean("invulnerable", false);
        this.breathCooldownMs = definition.getInt("breathCooldownSeconds", 6) * 1000L;
        this.highlightRadius = definition.getDouble("playerHighlightRadius", 85.0);
    }

    public boolean start() {
        World world = game.getArena().getWorld();
        if (world == null) {
            return false;
        }

        volcanoPhase.pauseAiGuardianForPlayer();

        int durationSeconds = definition.getInt("durationSeconds", 60);
        endTimeMs = System.currentTimeMillis() + durationSeconds * 1000L;
        lastBreathMs = 0;

        Location spawnLoc = player.getLocation().clone();
        spawnLoc.setY(spawnLoc.getY() + 10);

        dragon = world.spawn(spawnLoc, EnderDragon.class, entity -> {
            entity.setInvulnerable(invulnerable);
            entity.setRemoveWhenFarAway(false);
            AttributeInstance scale = entity.getAttribute(Attribute.GENERIC_SCALE);
            if (scale != null) {
                scale.setBaseValue(guardianScale);
            }
            ArenaEntityUtil.tagPlayerPilotGuardian(plugin, entity);
        });
        dragon.setPhase(EnderDragon.Phase.CIRCLING);
        EnderDragonMovement.steer(dragon, spawnLoc, EnderDragonMovement.Style.PATROL);

        player.setGameMode(GameMode.SPECTATOR);
        player.setInvisible(true);
        player.setAllowFlight(true);
        player.setFlying(true);
        dragon.addPassenger(player);

        world.playSound(spawnLoc, Sound.ENTITY_ENDER_DRAGON_GROWL, 3f, 0.65f);

        task = new BukkitRunnable() {
            @Override
            public void run() {
                tick();
            }
        }.runTaskTimer(plugin, 1L, 1L);

        return true;
    }

    public void tryFireBreath() {
        if (dragon == null || dragon.isDead() || !player.isOnline()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastBreathMs < breathCooldownMs) {
            return;
        }
        lastBreathMs = now;

        Location target = getLookTarget(80);
        Location breathCenter = target != null ? target : dragon.getLocation();
        World world = dragon.getWorld();
        if (world != null) {
            VolcanoGuardianBreath.fire(game, world, breathCenter, phaseConfig, guardianScale, player);
        }
    }

    public void end(boolean broadcast) {
        if (task != null) {
            task.cancel();
            task = null;
        }

        clearHighlights();

        if (dragon != null && !dragon.isDead()) {
            dragon.eject();
            dragon.remove();
        }
        dragon = null;

        if (player.isOnline()) {
            Location restoreLoc = dragon != null && !dragon.isDead()
                    ? dragon.getLocation()
                    : savedState.location();
            SavedPlayerState restoreSnapshot = new SavedPlayerState(
                    savedState.inventory(),
                    savedState.armor(),
                    savedState.offHand(),
                    restoreLoc,
                    GameMode.SURVIVAL,
                    savedState.totalExperience());
            PlayerSession.applySavedState(player, restoreSnapshot);
        }

        volcanoPhase.restoreAiGuardianIfNeeded();

        if (broadcast) {
            String message = plugin.formatMessage("&7The Volcano Guardian power has faded.");
            for (Player recipient : game.getNotificationRecipients()) {
                recipient.sendMessage(message);
            }
        }
    }

    public boolean isActive() {
        return dragon != null && !dragon.isDead() && System.currentTimeMillis() < endTimeMs;
    }

    public Player getPlayer() {
        return player;
    }

    public EnderDragon getDragon() {
        return dragon;
    }

    private void tick() {
        if (!player.isOnline() || game.getState() != GameState.ACTIVE) {
            end(true);
            plugin.getPowerUpManager().clearGuardianPilot(this);
            return;
        }
        if (System.currentTimeMillis() >= endTimeMs) {
            end(true);
            plugin.getPowerUpManager().clearGuardianPilot(this);
            return;
        }
        if (dragon == null || dragon.isDead()) {
            end(true);
            plugin.getPowerUpManager().clearGuardianPilot(this);
            return;
        }

        Location target = getLookTarget(120);
        if (target != null) {
            EnderDragonMovement.steer(dragon, target, EnderDragonMovement.Style.CHARGE);
        }

        updateHighlights();
        spawnWingParticles();
    }

    private Location getLookTarget(double range) {
        RayTraceResult trace = player.rayTraceBlocks(range);
        if (trace != null && trace.getHitPosition() != null) {
            Vector hit = trace.getHitPosition();
            return new Location(player.getWorld(), hit.getX(), hit.getY(), hit.getZ());
        }
        Vector direction = player.getLocation().getDirection().normalize().multiply(range);
        return player.getEyeLocation().add(direction);
    }

    private void updateHighlights() {
        if (dragon == null) {
            return;
        }
        Location center = dragon.getLocation();
        double radiusSq = highlightRadius * highlightRadius;
        Set<UUID> currentlyHighlighted = new HashSet<>();

        for (Player other : game.getAlivePlayers()) {
            if (other.equals(player)) {
                continue;
            }
            if (other.getLocation().distanceSquared(center) <= radiusSq) {
                currentlyHighlighted.add(other.getUniqueId());
                if (!highlightedBySession.contains(other.getUniqueId())) {
                    other.setGlowing(true);
                }
            }
        }

        for (UUID uuid : new HashSet<>(highlightedBySession)) {
            if (!currentlyHighlighted.contains(uuid)) {
                Player other = plugin.getServer().getPlayer(uuid);
                if (other != null && other.isOnline()) {
                    if (!plugin.getGameManager().isInRespawnGrace(game, other)) {
                        other.setGlowing(false);
                    }
                }
            }
        }

        highlightedBySession.clear();
        highlightedBySession.addAll(currentlyHighlighted);
    }

    private void clearHighlights() {
        for (UUID uuid : highlightedBySession) {
            Player other = plugin.getServer().getPlayer(uuid);
            if (other != null && other.isOnline()) {
                if (!plugin.getGameManager().isInRespawnGrace(game, other)) {
                    other.setGlowing(false);
                }
            }
        }
        highlightedBySession.clear();
    }

    private void spawnWingParticles() {
        if (dragon == null) {
            return;
        }
        Location loc = dragon.getLocation();
        World world = loc.getWorld();
        if (world == null) {
            return;
        }
        ParticleUtil.spawn(world, Particle.SOUL_FIRE_FLAME,
                loc.getX(), loc.getY(), loc.getZ(), 3, 1.5, 0.6, 1.5, 0.01);
    }
}
