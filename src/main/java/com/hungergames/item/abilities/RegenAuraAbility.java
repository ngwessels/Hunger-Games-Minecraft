package com.hungergames.item.abilities;

import com.hungergames.item.AbilityType;
import com.hungergames.item.CustomItemAbility;
import com.hungergames.item.CustomItemDefinition;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

public class RegenAuraAbility implements CustomItemAbility {

    @Override
    public AbilityType type() {
        return AbilityType.REGEN_AURA;
    }

    @Override
    public void onActivate(Player player, CustomItemDefinition definition, PlayerInteractEvent event) {
    }

    @Override
    public void applyPassive(Player player, CustomItemDefinition definition) {
        int amplifier = definition.getInt("amplifier", 0);
        player.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 100, amplifier, false, false, true));
    }

    @Override
    public void removePassive(Player player, CustomItemDefinition definition) {
        player.removePotionEffect(PotionEffectType.REGENERATION);
    }
}
