package com.hungergames.item;

import com.hungergames.HungerGamesPlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

public class AbilityUseGuard {

    private final HungerGamesPlugin plugin;
    private final CustomItemRegistry registry;
    private final AbilityCooldownManager cooldownManager;

    public AbilityUseGuard(HungerGamesPlugin plugin, CustomItemRegistry registry, AbilityCooldownManager cooldownManager) {
        this.plugin = plugin;
        this.registry = registry;
        this.cooldownManager = cooldownManager;
    }

    public boolean tryActivate(Player player, ItemStack item, CustomItemDefinition definition, PlayerInteractEvent event) {
        String itemId = definition.getId();
        if (cooldownManager.isOnCooldown(player, itemId)) {
            cooldownManager.sendCooldownMessage(plugin, player,
                    (int) cooldownManager.getRemainingSeconds(player, itemId));
            event.setCancelled(true);
            return false;
        }
        if (!hasCharges(item, definition)) {
            player.sendMessage(plugin.formatMessage("&cThis item has no uses remaining."));
            event.setCancelled(true);
            return false;
        }
        return true;
    }

    public boolean tryStartWithoutCooldown(Player player, ItemStack item, CustomItemDefinition definition, PlayerInteractEvent event) {
        if (!hasCharges(item, definition)) {
            player.sendMessage(plugin.formatMessage("&cThis item has no uses remaining."));
            event.setCancelled(true);
            return false;
        }
        return true;
    }

    public boolean isOnCooldown(Player player, CustomItemDefinition definition) {
        return cooldownManager.isOnCooldown(player, definition.getId());
    }

    public void applyCooldown(Player player, CustomItemDefinition definition) {
        int cooldown = definition.getInt("cooldownSeconds", 0);
        if (cooldown > 0) {
            cooldownManager.setCooldown(player, definition.getId(), cooldown);
        }
    }

    public void finishUse(Player player, ItemStack item, CustomItemDefinition definition, EquipmentSlot hand) {
        int remaining = decrementCharges(item, definition);
        registry.refreshItemLore(item, definition, remaining);
        applyCooldown(player, definition);
        updateItemInHand(player, item, hand, remaining);
    }

    public boolean consumeCharge(Player player, ItemStack item, CustomItemDefinition definition, EquipmentSlot hand) {
        if (!hasCharges(item, definition)) {
            return false;
        }
        int remaining = decrementCharges(item, definition);
        registry.refreshItemLore(item, definition, remaining);
        updateItemInHand(player, item, hand, remaining);
        return remaining > 0;
    }

    public int getRemainingUses(ItemStack item, CustomItemDefinition definition) {
        int maxUses = definition.getInt("maxUses", 0);
        if (maxUses <= 0) {
            return Integer.MAX_VALUE;
        }
        if (item == null || !item.hasItemMeta()) {
            return maxUses;
        }
        Integer stored = item.getItemMeta().getPersistentDataContainer()
                .get(CustomItemRegistry.USES_REMAINING_KEY, PersistentDataType.INTEGER);
        return stored != null ? stored : maxUses;
    }

    public boolean hasCharges(ItemStack item, CustomItemDefinition definition) {
        return getRemainingUses(item, definition) > 0;
    }

    private int decrementCharges(ItemStack item, CustomItemDefinition definition) {
        int maxUses = definition.getInt("maxUses", 0);
        if (maxUses <= 0 || item == null || !item.hasItemMeta()) {
            return Integer.MAX_VALUE;
        }
        int remaining = getRemainingUses(item, definition) - 1;
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer()
                .set(CustomItemRegistry.USES_REMAINING_KEY, PersistentDataType.INTEGER, Math.max(0, remaining));
        item.setItemMeta(meta);
        return remaining;
    }

    private void updateItemInHand(Player player, ItemStack item, EquipmentSlot hand, int remaining) {
        if (remaining <= 0) {
            String name = item != null && item.hasItemMeta() ? item.getItemMeta().getDisplayName() : "item";
            if (hand == EquipmentSlot.HAND) {
                player.getInventory().setItemInMainHand(null);
            } else if (hand == EquipmentSlot.OFF_HAND) {
                player.getInventory().setItemInOffHand(null);
            }
            player.sendMessage(plugin.formatMessage("&cYour &f" + name + " &chas been depleted."));
            return;
        }
        if (hand == EquipmentSlot.HAND) {
            player.getInventory().setItemInMainHand(item);
        } else if (hand == EquipmentSlot.OFF_HAND) {
            player.getInventory().setItemInOffHand(item);
        }
    }
}
