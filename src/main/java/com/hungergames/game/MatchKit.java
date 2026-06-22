package com.hungergames.game;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

public final class MatchKit {

    private static final int ARROW_STACKS = 3;
    private static final int ARROWS_PER_STACK = 64;
    private static final int DIRT_STACKS = 3;
    private static final int DIRT_PER_STACK = 64;

    private MatchKit() {
    }

    public static void apply(Player player) {
        PlayerInventory inventory = player.getInventory();
        inventory.clear();
        inventory.setArmorContents(new ItemStack[4]);
        inventory.setItemInOffHand(null);

        inventory.setHelmet(new ItemStack(Material.IRON_HELMET));
        inventory.setChestplate(new ItemStack(Material.IRON_CHESTPLATE));
        inventory.setLeggings(new ItemStack(Material.IRON_LEGGINGS));
        inventory.setBoots(new ItemStack(Material.IRON_BOOTS));

        inventory.setItem(0, new ItemStack(Material.IRON_SWORD));
        inventory.setItem(1, new ItemStack(Material.BOW));
        inventory.setItem(2, new ItemStack(Material.IRON_PICKAXE));
        inventory.setItem(3, new ItemStack(Material.IRON_AXE));
        inventory.setItem(4, new ItemStack(Material.IRON_SHOVEL));

        for (int i = 0; i < ARROW_STACKS; i++) {
            inventory.setItem(5 + i, new ItemStack(Material.ARROW, ARROWS_PER_STACK));
        }

        for (int i = 0; i < DIRT_STACKS; i++) {
            inventory.setItem(8 + i, new ItemStack(Material.DIRT, DIRT_PER_STACK));
        }
    }

    public static void restoreVitals(Player player) {
        player.setHealth(player.getMaxHealth());
        player.setFoodLevel(20);
        player.setSaturation(20f);
        player.setExhaustion(0f);
    }
}
