package com.hungergames.listener;

import com.hungergames.HungerGamesPlugin;
import com.hungergames.powerup.PlayerGuardianSession;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public class GuardianPilotListener implements Listener {

    private final HungerGamesPlugin plugin;

    public GuardianPilotListener(HungerGamesPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.LEFT_CLICK_AIR && event.getAction() != Action.LEFT_CLICK_BLOCK) {
            return;
        }
        plugin.getPowerUpManager().getActiveGuardianPilot().ifPresent(session -> {
            if (session.getPlayer().equals(event.getPlayer())) {
                session.tryFireBreath();
                event.setCancelled(true);
            }
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        endSessionIfPilot(event.getPlayer().getUniqueId());
    }

    private void endSessionIfPilot(java.util.UUID uuid) {
        plugin.getPowerUpManager().getActiveGuardianPilot().ifPresent(session -> {
            if (session.getPlayer().getUniqueId().equals(uuid)) {
                session.end(false);
                plugin.getPowerUpManager().clearGuardianPilot(session);
            }
        });
    }
}
