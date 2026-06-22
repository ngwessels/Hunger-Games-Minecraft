package com.hungergames.item.abilities;

import com.hungergames.HungerGamesPlugin;
import com.hungergames.game.Game;
import com.hungergames.item.AbilityType;
import com.hungergames.item.AbilityUseGuard;
import com.hungergames.item.CustomItemAbility;
import com.hungergames.item.CustomItemDefinition;
import com.hungergames.item.CustomItemRegistry;
import com.hungergames.util.GameCombatUtil;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.entity.Snowball;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.UUID;

public class RedShellAbility implements CustomItemAbility {

    public static final String PROJECTILE_TYPE = "red_shell";
    public static final NamespacedKey PROJECTILE_STUN_KEY = new NamespacedKey("hungergames", "red_shell_stun_ticks");

    private final HungerGamesPlugin plugin;
    private final AbilityUseGuard useGuard;

    public RedShellAbility(HungerGamesPlugin plugin, AbilityUseGuard useGuard) {
        this.plugin = plugin;
        this.useGuard = useGuard;
    }

    @Override
    public AbilityType type() {
        return AbilityType.RED_SHELL;
    }

    @Override
    public void onActivate(Player player, CustomItemDefinition definition, PlayerInteractEvent event) {
        ItemStack item = event.getItem();
        if (!useGuard.tryActivate(player, item, definition, event)) {
            return;
        }

        double speed = definition.getDouble("speed", 1.2);
        int homingRange = definition.getInt("homingRange", 40);
        int maxLifetimeTicks = definition.getInt("maxLifetimeTicks", 200);

        int stunDurationSeconds = definition.getInt("stunDurationSeconds", 3);
        int stunTicks = stunDurationSeconds * 20;

        Snowball shell = player.launchProjectile(Snowball.class);
        shell.setShooter(player);
        Vector direction = player.getLocation().getDirection().normalize().multiply(speed);
        shell.setVelocity(direction);

        shell.getPersistentDataContainer().set(
                CustomItemRegistry.PROJECTILE_TYPE_KEY, PersistentDataType.STRING, PROJECTILE_TYPE);
        shell.getPersistentDataContainer().set(
                CustomItemRegistry.PROJECTILE_OWNER_KEY, PersistentDataType.STRING, player.getUniqueId().toString());
        shell.getPersistentDataContainer().set(
                PROJECTILE_STUN_KEY, PersistentDataType.INTEGER, stunTicks);

        trackShell(shell, player, homingRange, maxLifetimeTicks, definition.getDouble("speed", 1.2));

        useGuard.finishUse(player, item, definition, event.getHand());
        event.setCancelled(true);
    }

    private void trackShell(Snowball shell, Player shooter, int homingRange, int maxLifetimeTicks, double speed) {
        new BukkitRunnable() {
            private int ticks = 0;

            @Override
            public void run() {
                if (!shell.isValid() || shell.isDead() || ticks >= maxLifetimeTicks) {
                    if (shell.isValid()) {
                        shell.remove();
                    }
                    cancel();
                    return;
                }

                shell.getWorld().spawnParticle(
                        Particle.DUST,
                        shell.getLocation(),
                        3,
                        0.1, 0.1, 0.1,
                        0,
                        new Particle.DustOptions(Color.RED, 1.0f)
                );

                Player target = findNearestTarget(shooter, shell.getLocation(), homingRange);
                if (target != null) {
                    Vector steer = target.getLocation().add(0, 1, 0).toVector()
                            .subtract(shell.getLocation().toVector())
                            .normalize()
                            .multiply(speed);
                    shell.setVelocity(steer);
                }

                ticks++;
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private Player findNearestTarget(Player shooter, Location location, int range) {
        Game game = GameCombatUtil.getActiveGame(plugin, shooter);
        if (game == null) {
            return null;
        }
        Player nearest = null;
        double nearestDist = range * range;
        for (Player candidate : location.getWorld().getPlayers()) {
            if (candidate.equals(shooter)) {
                continue;
            }
            if (!GameCombatUtil.areSameGame(plugin, shooter, candidate)) {
                continue;
            }
            double dist = candidate.getLocation().distanceSquared(location);
            if (dist < nearestDist) {
                nearestDist = dist;
                nearest = candidate;
            }
        }
        return nearest;
    }

    public void handleProjectileHit(ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof Snowball shell)) {
            return;
        }
        String type = shell.getPersistentDataContainer()
                .get(CustomItemRegistry.PROJECTILE_TYPE_KEY, PersistentDataType.STRING);
        if (!PROJECTILE_TYPE.equals(type)) {
            return;
        }

        int stunDuration = shell.getPersistentDataContainer()
                .getOrDefault(PROJECTILE_STUN_KEY, PersistentDataType.INTEGER, 60);
        if (event.getHitEntity() instanceof Player victim) {
            String ownerId = shell.getPersistentDataContainer()
                    .get(CustomItemRegistry.PROJECTILE_OWNER_KEY, PersistentDataType.STRING);
            if (ownerId == null) {
                return;
            }
            Player shooter = plugin.getServer().getPlayer(UUID.fromString(ownerId));
            if (shooter == null || !GameCombatUtil.areSameGame(plugin, shooter, victim)) {
                return;
            }
            if (shooter.equals(victim)) {
                return;
            }
            victim.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, stunDuration, 3, false, false, true));
            victim.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, stunDuration, 1, false, false, true));
        }
        shell.remove();
    }

    public void handleProjectileDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Snowball shell)) {
            return;
        }
        String type = shell.getPersistentDataContainer()
                .get(CustomItemRegistry.PROJECTILE_TYPE_KEY, PersistentDataType.STRING);
        if (PROJECTILE_TYPE.equals(type)) {
            event.setCancelled(true);
        }
    }

    @Override
    public void applyPassive(Player player, CustomItemDefinition definition) {
    }

    @Override
    public void removePassive(Player player, CustomItemDefinition definition) {
    }
}
