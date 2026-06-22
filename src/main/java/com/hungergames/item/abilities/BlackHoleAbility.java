package com.hungergames.item.abilities;

import com.hungergames.HungerGamesPlugin;
import com.hungergames.item.AbilityType;
import com.hungergames.item.AbilityUseGuard;
import com.hungergames.item.CustomItemAbility;
import com.hungergames.item.CustomItemDefinition;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

public class BlackHoleAbility implements CustomItemAbility {

    private final HungerGamesPlugin plugin;
    private final AbilityUseGuard useGuard;

    public BlackHoleAbility(HungerGamesPlugin plugin, AbilityUseGuard useGuard) {
        this.plugin = plugin;
        this.useGuard = useGuard;
    }

    @Override
    public AbilityType type() {
        return AbilityType.BLACK_HOLE;
    }

    @Override
    public void onActivate(Player player, CustomItemDefinition definition, PlayerInteractEvent event) {
        ItemStack item = event.getItem();
        if (!useGuard.tryActivate(player, item, definition, event)) {
            return;
        }

        double radius = definition.getDouble("radius", 8);
        int durationTicks = definition.getInt("durationTicks", 60);
        double pullStrength = definition.getDouble("pullStrength", 0.4);

        Location center = player.getTargetBlockExact(30) != null
                ? player.getTargetBlockExact(30).getLocation().add(0.5, 0.5, 0.5)
                : player.getLocation().add(player.getLocation().getDirection().multiply(5));

        new BukkitRunnable() {
            private int ticks = 0;

            @Override
            public void run() {
                if (ticks >= durationTicks) {
                    cancel();
                    return;
                }
                center.getWorld().spawnParticle(Particle.PORTAL, center, 30, radius / 3, 0.5, radius / 3, 0.1);
                center.getWorld().spawnParticle(Particle.REVERSE_PORTAL, center, 15, radius / 4, 0.3, radius / 4, 0.05);

                for (Entity entity : center.getWorld().getNearbyEntities(center, radius, radius, radius)) {
                    if (entity.equals(player)) {
                        continue;
                    }
                    Vector pull = center.toVector().subtract(entity.getLocation().toVector()).normalize()
                            .multiply(pullStrength);
                    entity.setVelocity(entity.getVelocity().add(pull));
                }
                ticks++;
            }
        }.runTaskTimer(plugin, 0L, 1L);

        useGuard.finishUse(player, item, definition, event.getHand());
        event.setCancelled(true);
    }

    @Override
    public void applyPassive(Player player, CustomItemDefinition definition) {
    }

    @Override
    public void removePassive(Player player, CustomItemDefinition definition) {
    }
}
