package com.hungergames.listener;

import com.hungergames.HungerGamesPlugin;
import com.hungergames.game.GameState;
import com.hungergames.item.AbilityType;
import com.hungergames.item.CustomItemDefinition;
import com.hungergames.item.CustomItemRegistry;
import com.hungergames.item.CustomItemType;
import com.hungergames.item.abilities.EggMachineGunAbility;
import com.hungergames.item.abilities.RedShellAbility;
import com.hungergames.util.GameCombatUtil;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

public class CustomItemListener implements Listener {

    private final HungerGamesPlugin plugin;

    public CustomItemListener(HungerGamesPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Player player = event.getPlayer();
        if (plugin.getCurseManager().isBeastCursed(player)) {
            event.setCancelled(true);
            return;
        }
        ItemStack item = event.getItem();
        if (item == null) {
            return;
        }

        CustomItemRegistry registry = plugin.getCustomItemRegistry();
        var defOpt = registry.getFromItem(item);
        if (defOpt.isEmpty()) {
            return;
        }

        CustomItemDefinition def = defOpt.get();
        if (def.getType() != CustomItemType.ACTIVE) {
            return;
        }

        var gameOpt = plugin.getGameManager().getPlayerGame(player);
        if (gameOpt.isEmpty() || gameOpt.get().getState() != GameState.ACTIVE) {
            return;
        }

        registry.getAbility(def.getAbility()).ifPresent(ability ->
                ability.onActivate(player, def, event));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onProjectileHit(ProjectileHitEvent event) {
        CustomItemRegistry registry = plugin.getCustomItemRegistry();
        registry.getAbility(AbilityType.RED_SHELL).ifPresent(ability -> {
            if (ability instanceof RedShellAbility redShell) {
                redShell.handleProjectileHit(event);
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onProjectileDamage(EntityDamageByEntityEvent event) {
        CustomItemRegistry registry = plugin.getCustomItemRegistry();
        registry.getAbility(AbilityType.RED_SHELL).ifPresent(ability -> {
            if (ability instanceof RedShellAbility redShell) {
                redShell.handleProjectileDamage(event);
            }
        });
        registry.getAbility(AbilityType.EGG_MACHINE_GUN).ifPresent(ability -> {
            if (ability instanceof EggMachineGunAbility eggGun) {
                eggGun.handleProjectileDamage(event);
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)) {
            return;
        }
        if (plugin.getCurseManager().isBeastCursed(attacker)) {
            return;
        }
        if (!GameCombatUtil.isActiveCombatant(plugin, attacker)) {
            return;
        }

        ItemStack weapon = attacker.getInventory().getItemInMainHand();
        CustomItemRegistry registry = plugin.getCustomItemRegistry();
        registry.getFromItem(weapon).ifPresent(def -> {
            if (def.getType() != CustomItemType.ON_ATTACK) {
                return;
            }
            registry.getAbility(def.getAbility()).ifPresent(ability ->
                    ability.onAttack(attacker, def, event));
        });
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamaged(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        if (!GameCombatUtil.isActiveCombatant(plugin, victim)) {
            return;
        }

        CustomItemRegistry registry = plugin.getCustomItemRegistry();
        for (ItemStack armor : victim.getInventory().getArmorContents()) {
            registry.getFromItem(armor).ifPresent(def -> {
                if (def.getType() != CustomItemType.PASSIVE_ARMOR) {
                    return;
                }
                if (def.getAbility() != com.hungergames.item.AbilityType.THORNS_AURA) {
                    return;
                }
                registry.getAbility(def.getAbility()).ifPresent(ability ->
                        ability.onDamaged(victim, def, event));
            });
        }
    }

    @EventHandler
    public void onItemHeld(PlayerItemHeldEvent event) {
        plugin.getCustomItemRegistry().getEggMachineGunAbility().stopFiring(event.getPlayer());
        updatePassives(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.getCustomItemRegistry().getEggMachineGunAbility().stopFiring(event.getPlayer());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        updatePassives(event.getPlayer());
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            plugin.getServer().getScheduler().runTask(plugin, () -> updatePassives(player));
        }
    }

    public void updatePassives(Player player) {
        CustomItemRegistry registry = plugin.getCustomItemRegistry();
        for (CustomItemDefinition def : registry.getDefinitions()) {
            registry.getAbility(def.getAbility()).ifPresent(ability -> ability.removePassive(player, def));
        }

        plugin.getGameManager().getPlayerGame(player).ifPresent(game -> {
            if (game.getState() != GameState.ACTIVE && game.getState() != GameState.GRACE) {
                return;
            }
            applyHeldPassives(player, registry, player.getInventory().getItemInMainHand());
            applyHeldPassives(player, registry, player.getInventory().getItemInOffHand());
            for (ItemStack armor : player.getInventory().getArmorContents()) {
                applyArmorPassives(player, registry, armor);
            }
        });
    }

    private void applyHeldPassives(Player player, CustomItemRegistry registry, ItemStack item) {
        registry.getFromItem(item).ifPresent(def -> {
            if (def.getType() == CustomItemType.PASSIVE_HELD) {
                registry.getAbility(def.getAbility()).ifPresent(ability -> ability.applyPassive(player, def));
            }
        });
    }

    private void applyArmorPassives(Player player, CustomItemRegistry registry, ItemStack item) {
        registry.getFromItem(item).ifPresent(def -> {
            if (def.getType() == CustomItemType.PASSIVE_ARMOR) {
                registry.getAbility(def.getAbility()).ifPresent(ability -> ability.applyPassive(player, def));
            }
        });
    }
}
