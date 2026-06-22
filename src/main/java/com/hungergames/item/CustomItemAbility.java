package com.hungergames.item;

import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEvent;

public interface CustomItemAbility {

    AbilityType type();

    void onActivate(Player player, CustomItemDefinition definition, PlayerInteractEvent event);

    void applyPassive(Player player, CustomItemDefinition definition);

    void removePassive(Player player, CustomItemDefinition definition);

    default void onAttack(Player attacker, CustomItemDefinition definition, EntityDamageByEntityEvent event) {
    }

    default void onDamaged(Player victim, CustomItemDefinition definition, EntityDamageEvent event) {
    }
}
