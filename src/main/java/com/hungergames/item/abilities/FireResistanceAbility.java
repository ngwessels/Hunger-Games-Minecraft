package com.hungergames.item.abilities;

import com.hungergames.item.AbilityType;
import com.hungergames.item.CustomItemAbility;
import com.hungergames.item.CustomItemDefinition;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

public class FireResistanceAbility implements CustomItemAbility {

    @Override
    public AbilityType type() {
        return AbilityType.FIRE_RESISTANCE;
    }

    @Override
    public void onActivate(Player player, CustomItemDefinition definition, PlayerInteractEvent event) {
    }

    @Override
    public void applyPassive(Player player, CustomItemDefinition definition) {
        player.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, Integer.MAX_VALUE, 0, false, false, true));
    }

    @Override
    public void removePassive(Player player, CustomItemDefinition definition) {
        player.removePotionEffect(PotionEffectType.FIRE_RESISTANCE);
    }
}
