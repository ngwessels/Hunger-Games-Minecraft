package com.hungergames.item.abilities;

import com.hungergames.HungerGamesPlugin;
import com.hungergames.item.AbilityType;
import com.hungergames.item.CustomItemAbility;
import com.hungergames.item.CustomItemDefinition;
import com.hungergames.util.GameCombatUtil;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEvent;

public class ThornsAuraAbility implements CustomItemAbility {

    private final HungerGamesPlugin plugin;

    public ThornsAuraAbility(HungerGamesPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public AbilityType type() {
        return AbilityType.THORNS_AURA;
    }

    @Override
    public void onActivate(Player player, CustomItemDefinition definition, PlayerInteractEvent event) {
    }

    @Override
    public void onDamaged(Player victim, CustomItemDefinition definition, EntityDamageEvent event) {
        if (!(event instanceof EntityDamageByEntityEvent damageEvent)) {
            return;
        }

        double reflectPercent = definition.getDouble("reflectPercent", 30) / 100.0;
        double reflectDamage = event.getDamage() * reflectPercent;

        if (GameCombatUtil.isSimulationGame(plugin, victim)) {
            if (!(damageEvent.getDamager() instanceof LivingEntity attacker) || attacker.equals(victim)) {
                return;
            }
            attacker.damage(reflectDamage, victim);
            return;
        }

        if (!(damageEvent.getDamager() instanceof Player attacker)) {
            return;
        }
        if (!GameCombatUtil.areSameGame(plugin, victim, attacker)) {
            return;
        }

        attacker.damage(reflectDamage, victim);
    }

    @Override
    public void applyPassive(Player player, CustomItemDefinition definition) {
    }

    @Override
    public void removePassive(Player player, CustomItemDefinition definition) {
    }
}
