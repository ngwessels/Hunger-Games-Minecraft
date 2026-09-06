package com.hungergames.game;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Snapshot of a tribute's in-match combat state, used to restore them after a disconnect.
 */
public record MatchSnapshot(
        ItemStack[] inventory,
        ItemStack[] armor,
        ItemStack offHand,
        Location location,
        GameMode gameMode,
        double health,
        int foodLevel,
        float saturation,
        int fireTicks,
        int totalExperience,
        double absorption,
        Collection<PotionEffect> potionEffects
) {
    public List<PotionEffect> copiedEffects() {
        List<PotionEffect> copy = new ArrayList<>();
        if (potionEffects != null) {
            copy.addAll(potionEffects);
        }
        return copy;
    }
}
