package com.hungergames.item.abilities;

import com.hungergames.HungerGamesPlugin;
import com.hungergames.item.AbilityType;
import com.hungergames.item.AbilityUseGuard;
import com.hungergames.item.CustomItemAbility;
import com.hungergames.item.CustomItemDefinition;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

public class ItemMagnetAbility implements CustomItemAbility {

    private final AbilityUseGuard useGuard;

    public ItemMagnetAbility(HungerGamesPlugin plugin, AbilityUseGuard useGuard) {
        this.useGuard = useGuard;
    }

    @Override
    public AbilityType type() {
        return AbilityType.ITEM_MAGNET;
    }

    @Override
    public void onActivate(Player player, CustomItemDefinition definition, PlayerInteractEvent event) {
        ItemStack item = event.getItem();
        if (!useGuard.tryActivate(player, item, definition, event)) {
            return;
        }

        double radius = definition.getDouble("radius", 10);
        double pullStrength = definition.getDouble("pullStrength", 0.8);

        for (Item dropped : player.getNearbyEntities(radius, radius, radius).stream()
                .filter(Item.class::isInstance)
                .map(Item.class::cast)
                .toList()) {
            Vector pull = player.getLocation().toVector()
                    .subtract(dropped.getLocation().toVector())
                    .normalize()
                    .multiply(pullStrength);
            dropped.setVelocity(dropped.getVelocity().add(pull));
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
