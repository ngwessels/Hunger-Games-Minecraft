package com.hungergames.item.abilities;

import com.hungergames.HungerGamesPlugin;
import com.hungergames.item.AbilityType;
import com.hungergames.item.AbilityUseGuard;
import com.hungergames.item.CustomItemAbility;
import com.hungergames.item.CustomItemDefinition;
import com.hungergames.util.GameCombatUtil;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;

public class BlinkAbility implements CustomItemAbility {

    private final HungerGamesPlugin plugin;
    private final AbilityUseGuard useGuard;

    public BlinkAbility(HungerGamesPlugin plugin, AbilityUseGuard useGuard) {
        this.plugin = plugin;
        this.useGuard = useGuard;
    }

    @Override
    public AbilityType type() {
        return AbilityType.BLINK;
    }

    @Override
    public void onActivate(Player player, CustomItemDefinition definition, PlayerInteractEvent event) {
        ItemStack item = event.getItem();
        if (!useGuard.tryActivate(player, item, definition, event)) {
            return;
        }

        int distance = definition.getInt("distance", 8);
        Location dest = GameCombatUtil.getBlinkDestination(player, distance);
        dest.setPitch(player.getLocation().getPitch());
        dest.setYaw(player.getLocation().getYaw());

        if (!GameCombatUtil.isInsideArena(plugin, player, dest)) {
            player.sendMessage(plugin.formatMessage("&cCannot blink outside the arena."));
            event.setCancelled(true);
            return;
        }

        if (!dest.getBlock().isPassable() || !dest.clone().add(0, 1, 0).getBlock().isPassable()) {
            player.sendMessage(plugin.formatMessage("&cDestination is blocked."));
            event.setCancelled(true);
            return;
        }

        player.getWorld().spawnParticle(Particle.PORTAL, player.getLocation(), 30, 0.3, 0.5, 0.3, 0.1);
        player.teleport(dest);
        player.getWorld().spawnParticle(Particle.PORTAL, dest, 30, 0.3, 0.5, 0.3, 0.1);
        player.playSound(dest, Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1.2f);
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
