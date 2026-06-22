package com.hungergames.item.abilities;

import com.hungergames.HungerGamesPlugin;
import com.hungergames.item.AbilityType;
import com.hungergames.item.CustomItemAbility;
import com.hungergames.item.CustomItemDefinition;
import com.hungergames.util.GameCombatUtil;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;

public class LifeStealAbility implements CustomItemAbility {

    private final HungerGamesPlugin plugin;

    public LifeStealAbility(HungerGamesPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public AbilityType type() {
        return AbilityType.LIFE_STEAL;
    }

    @Override
    public void onActivate(Player player, CustomItemDefinition definition, PlayerInteractEvent event) {
    }

    @Override
    public void onAttack(Player attacker, CustomItemDefinition definition, EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof LivingEntity victim) || victim.equals(attacker)) {
            return;
        }
        boolean simulation = GameCombatUtil.isSimulationGame(plugin, attacker);
        if (!simulation) {
            if (!(victim instanceof Player playerVictim)) {
                return;
            }
            if (!GameCombatUtil.areSameGame(plugin, attacker, playerVictim)) {
                return;
            }
        }

        double healPercent = definition.getDouble("healPercent", 25) / 100.0;
        double heal = event.getFinalDamage() * healPercent;
        double newHealth = Math.min(attacker.getMaxHealth(), attacker.getHealth() + heal);
        attacker.setHealth(newHealth);
    }

    @Override
    public void applyPassive(Player player, CustomItemDefinition definition) {
    }

    @Override
    public void removePassive(Player player, CustomItemDefinition definition) {
    }
}
