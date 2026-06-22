package com.hungergames.item.abilities;

import com.hungergames.HungerGamesPlugin;
import com.hungergames.item.AbilityType;
import com.hungergames.item.AbilityUseGuard;
import com.hungergames.item.CustomItemAbility;
import com.hungergames.item.CustomItemDefinition;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

public class SpeedBurstAbility implements CustomItemAbility {

    private final AbilityUseGuard useGuard;

    public SpeedBurstAbility(HungerGamesPlugin plugin, AbilityUseGuard useGuard) {
        this.useGuard = useGuard;
    }

    @Override
    public AbilityType type() {
        return AbilityType.SPEED_BURST;
    }

    @Override
    public void onActivate(Player player, CustomItemDefinition definition, PlayerInteractEvent event) {
        ItemStack item = event.getItem();
        if (!useGuard.tryActivate(player, item, definition, event)) {
            return;
        }
        int duration = definition.getInt("durationSeconds", 5) * 20;
        int amplifier = definition.getInt("amplifier", 2) - 1;
        player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, duration, amplifier));
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
