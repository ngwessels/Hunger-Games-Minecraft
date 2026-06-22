package com.hungergames.item.abilities;

import com.hungergames.HungerGamesPlugin;
import com.hungergames.game.Game;
import com.hungergames.item.AbilityType;
import com.hungergames.item.AbilityUseGuard;
import com.hungergames.item.CustomItemAbility;
import com.hungergames.item.CustomItemDefinition;
import com.hungergames.util.GameCombatUtil;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

public class IceSnareAbility implements CustomItemAbility {

    private final HungerGamesPlugin plugin;
    private final AbilityUseGuard useGuard;

    public IceSnareAbility(HungerGamesPlugin plugin, AbilityUseGuard useGuard) {
        this.plugin = plugin;
        this.useGuard = useGuard;
    }

    @Override
    public AbilityType type() {
        return AbilityType.ICE_SNARE;
    }

    @Override
    public void onActivate(Player player, CustomItemDefinition definition, PlayerInteractEvent event) {
        ItemStack item = event.getItem();
        if (!useGuard.tryActivate(player, item, definition, event)) {
            return;
        }

        int range = definition.getInt("range", 20);
        double radius = definition.getDouble("radius", 5);
        int durationSeconds = definition.getInt("durationSeconds", 4);
        int slownessAmp = definition.getInt("slownessAmplifier", 2) - 1;
        Location center = GameCombatUtil.getTargetLocation(player, range);

        if (!GameCombatUtil.isInsideArena(plugin, player, center)) {
            player.sendMessage(plugin.formatMessage("&cTarget is outside the arena."));
            event.setCancelled(true);
            return;
        }

        Game game = GameCombatUtil.getActiveGame(plugin, player);
        boolean simulation = game != null && game.isSimulationMode();
        player.playSound(player.getLocation(), Sound.BLOCK_GLASS_BREAK, 1f, 0.5f);

        new BukkitRunnable() {
            private int ticks = 0;
            private final int maxTicks = durationSeconds * 20;

            @Override
            public void run() {
                if (ticks >= maxTicks) {
                    cancel();
                    return;
                }
                center.getWorld().spawnParticle(Particle.SNOWFLAKE, center, 20, radius / 2, 0.3, radius / 2, 0.02);
                for (Entity entity : center.getWorld().getNearbyEntities(center, radius, radius, radius)) {
                    if (entity.equals(player)) {
                        continue;
                    }
                    LivingEntity target;
                    if (simulation) {
                        if (!(entity instanceof LivingEntity living)) {
                            continue;
                        }
                        target = living;
                    } else {
                        if (!(entity instanceof Player playerTarget)) {
                            continue;
                        }
                        if (game == null || !game.isParticipant(playerTarget.getUniqueId())) {
                            continue;
                        }
                        target = playerTarget;
                    }
                    target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 40, slownessAmp, false, false, true));
                }
                ticks += 10;
            }
        }.runTaskTimer(plugin, 0L, 10L);

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
