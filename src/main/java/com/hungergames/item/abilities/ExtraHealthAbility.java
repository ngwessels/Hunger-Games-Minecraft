package com.hungergames.item.abilities;

import com.hungergames.item.AbilityType;
import com.hungergames.item.CustomItemAbility;
import com.hungergames.item.CustomItemDefinition;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerInteractEvent;

import java.util.UUID;

public class ExtraHealthAbility implements CustomItemAbility {

    private static final UUID MODIFIER_UUID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890");

    @Override
    public AbilityType type() {
        return AbilityType.EXTRA_HEALTH;
    }

    @Override
    public void onActivate(Player player, CustomItemDefinition definition, PlayerInteractEvent event) {
    }

    @Override
    public void applyPassive(Player player, CustomItemDefinition definition) {
        int bonusHearts = definition.getInt("bonusHearts", 4);
        AttributeInstance health = player.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        if (health == null) {
            return;
        }
        health.getModifiers().stream()
                .filter(m -> m.getUniqueId().equals(MODIFIER_UUID))
                .forEach(health::removeModifier);
        health.addModifier(new AttributeModifier(MODIFIER_UUID, "hg_extra_health",
                bonusHearts * 2.0, AttributeModifier.Operation.ADD_NUMBER));
        if (player.getHealth() > player.getMaxHealth()) {
            player.setHealth(player.getMaxHealth());
        }
    }

    @Override
    public void removePassive(Player player, CustomItemDefinition definition) {
        AttributeInstance health = player.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        if (health == null) {
            return;
        }
        health.getModifiers().stream()
                .filter(m -> m.getUniqueId().equals(MODIFIER_UUID))
                .forEach(health::removeModifier);
        if (player.getHealth() > player.getMaxHealth()) {
            player.setHealth(player.getMaxHealth());
        }
    }
}
