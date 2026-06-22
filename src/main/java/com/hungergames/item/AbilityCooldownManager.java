package com.hungergames.item;

import com.hungergames.HungerGamesPlugin;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class AbilityCooldownManager {

    private final Map<UUID, Map<String, Long>> cooldowns = new HashMap<>();

    public boolean isOnCooldown(Player player, String itemId) {
        Map<String, Long> playerCooldowns = cooldowns.get(player.getUniqueId());
        if (playerCooldowns == null) {
            return false;
        }
        Long end = playerCooldowns.get(itemId);
        return end != null && System.currentTimeMillis() < end;
    }

    public long getRemainingSeconds(Player player, String itemId) {
        Map<String, Long> playerCooldowns = cooldowns.get(player.getUniqueId());
        if (playerCooldowns == null) {
            return 0;
        }
        Long end = playerCooldowns.get(itemId);
        if (end == null) {
            return 0;
        }
        return Math.max(0, (end - System.currentTimeMillis()) / 1000);
    }

    public void setCooldown(Player player, String itemId, int seconds) {
        cooldowns.computeIfAbsent(player.getUniqueId(), k -> new HashMap<>())
                .put(itemId, System.currentTimeMillis() + seconds * 1000L);
    }

    public void clear(Player player) {
        cooldowns.remove(player.getUniqueId());
    }

    public void sendCooldownMessage(HungerGamesPlugin plugin, Player player, int cooldownSeconds) {
        player.sendMessage(plugin.formatMessage("&cAbility on cooldown: &f" + cooldownSeconds + "s"));
    }
}
