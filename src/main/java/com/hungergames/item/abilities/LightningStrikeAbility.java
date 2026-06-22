package com.hungergames.item.abilities;

import com.hungergames.HungerGamesPlugin;
import com.hungergames.item.AbilityType;
import com.hungergames.item.AbilityUseGuard;
import com.hungergames.item.CustomItemAbility;
import com.hungergames.item.CustomItemDefinition;
import com.hungergames.util.GameCombatUtil;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;

public class LightningStrikeAbility implements CustomItemAbility {

    private final HungerGamesPlugin plugin;
    private final AbilityUseGuard useGuard;

    public LightningStrikeAbility(HungerGamesPlugin plugin, AbilityUseGuard useGuard) {
        this.plugin = plugin;
        this.useGuard = useGuard;
    }

    @Override
    public AbilityType type() {
        return AbilityType.LIGHTNING_STRIKE;
    }

    @Override
    public void onActivate(Player player, CustomItemDefinition definition, PlayerInteractEvent event) {
        ItemStack item = event.getItem();
        if (!useGuard.tryActivate(player, item, definition, event)) {
            return;
        }

        int range = definition.getInt("range", 30);
        Location target = GameCombatUtil.getTargetLocation(player, range);
        if (!GameCombatUtil.isInsideArena(plugin, player, target)) {
            player.sendMessage(plugin.formatMessage("&cTarget is outside the arena."));
            event.setCancelled(true);
            return;
        }

        player.getWorld().strikeLightning(target);
        player.playSound(player.getLocation(), Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 1f, 1f);
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
