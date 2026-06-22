package com.hungergames.item.abilities;

import com.hungergames.HungerGamesPlugin;
import com.hungergames.game.Game;
import com.hungergames.item.AbilityType;
import com.hungergames.item.AbilityUseGuard;
import com.hungergames.item.CustomItemAbility;
import com.hungergames.item.CustomItemDefinition;
import com.hungergames.util.GameCombatUtil;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

public class ShockwaveAbility implements CustomItemAbility {

    private final HungerGamesPlugin plugin;
    private final AbilityUseGuard useGuard;

    public ShockwaveAbility(HungerGamesPlugin plugin, AbilityUseGuard useGuard) {
        this.plugin = plugin;
        this.useGuard = useGuard;
    }

    @Override
    public AbilityType type() {
        return AbilityType.SHOCKWAVE;
    }

    @Override
    public void onActivate(Player player, CustomItemDefinition definition, PlayerInteractEvent event) {
        ItemStack item = event.getItem();
        if (!useGuard.tryActivate(player, item, definition, event)) {
            return;
        }

        double radius = definition.getDouble("radius", 6);
        double knockback = definition.getDouble("knockback", 1.5);
        double damage = definition.getDouble("damage", 3.0);
        Game game = GameCombatUtil.getActiveGame(plugin, player);
        boolean simulation = game != null && game.isSimulationMode();

        player.getWorld().spawnParticle(Particle.EXPLOSION, player.getLocation(), 3, radius / 3, 0.2, radius / 3, 0);
        player.playSound(player.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 0.8f, 1.2f);

        for (Entity entity : player.getNearbyEntities(radius, radius, radius)) {
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
            Vector push = target.getLocation().toVector()
                    .subtract(player.getLocation().toVector())
                    .normalize()
                    .multiply(knockback)
                    .setY(0.4);
            target.setVelocity(push);
            target.damage(damage, player);
        }

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
