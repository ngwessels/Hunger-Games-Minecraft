package com.hungergames.item.abilities;

import com.hungergames.HungerGamesPlugin;
import com.hungergames.item.AbilityType;
import com.hungergames.item.AbilityUseGuard;
import com.hungergames.item.CustomItemAbility;
import com.hungergames.item.CustomItemDefinition;
import org.bukkit.entity.Fireball;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

public class GhastBlastAbility implements CustomItemAbility {

    private final AbilityUseGuard useGuard;

    public GhastBlastAbility(HungerGamesPlugin plugin, AbilityUseGuard useGuard) {
        this.useGuard = useGuard;
    }

    @Override
    public AbilityType type() {
        return AbilityType.GHAST_BLAST;
    }

    @Override
    public void onActivate(Player player, CustomItemDefinition definition, PlayerInteractEvent event) {
        ItemStack item = event.getItem();
        if (!useGuard.tryActivate(player, item, definition, event)) {
            return;
        }

        float yield = (float) definition.getDouble("yield", 1.5);
        Fireball fireball = player.launchProjectile(Fireball.class);
        Vector direction = player.getLocation().getDirection().normalize().multiply(2);
        fireball.setVelocity(direction);
        fireball.setYield(yield);
        fireball.setIsIncendiary(false);
        fireball.setShooter(player);

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
