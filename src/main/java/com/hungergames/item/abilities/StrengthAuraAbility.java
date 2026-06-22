package com.hungergames.item.abilities;

import com.hungergames.item.AbilityType;
import com.hungergames.item.CustomItemAbility;
import com.hungergames.item.CustomItemDefinition;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

public class StrengthAuraAbility implements CustomItemAbility {

    @Override
    public AbilityType type() {
        return AbilityType.STRENGTH_AURA;
    }

    @Override
    public void onActivate(Player player, CustomItemDefinition definition, PlayerInteractEvent event) {
    }

    @Override
    public void applyPassive(Player player, CustomItemDefinition definition) {
        int amplifier = definition.getInt("amplifier", 1) - 1;
        player.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, Integer.MAX_VALUE, amplifier, false, false, true));
    }

    @Override
    public void removePassive(Player player, CustomItemDefinition definition) {
        player.removePotionEffect(PotionEffectType.STRENGTH);
    }
}
