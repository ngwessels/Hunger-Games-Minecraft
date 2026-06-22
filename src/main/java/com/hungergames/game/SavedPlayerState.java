package com.hungergames.game;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;

/**
 * Snapshot of a player's pre-arena inventory and location, used when leaving a match.
 */
public record SavedPlayerState(
        ItemStack[] inventory,
        ItemStack[] armor,
        ItemStack offHand,
        Location location,
        GameMode gameMode,
        int totalExperience
) {
}
