package com.hungergames.util;

import com.hungergames.HungerGamesPlugin;
import com.hungergames.arena.Arena;
import com.hungergames.game.Game;
import com.hungergames.game.GameState;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

public final class GameCombatUtil {

    private GameCombatUtil() {
    }

    public static boolean isActiveCombatant(HungerGamesPlugin plugin, Player player) {
        return plugin.getGameManager().getPlayerGame(player)
                .map(game -> game.getState() == GameState.ACTIVE)
                .orElse(false);
    }

    public static boolean areSameGame(HungerGamesPlugin plugin, Player a, Player b) {
        var gameA = plugin.getGameManager().getPlayerGame(a);
        var gameB = plugin.getGameManager().getPlayerGame(b);
        return gameA.isPresent() && gameA.equals(gameB)
                && gameA.get().getState() == GameState.ACTIVE;
    }

    public static Location getTargetLocation(Player player, int range) {
        RayTraceResult result = player.getWorld().rayTraceBlocks(
                player.getEyeLocation(),
                player.getEyeLocation().getDirection(),
                range
        );
        if (result != null && result.getHitBlock() != null) {
            Block block = result.getHitBlock();
            return block.getLocation().add(0.5, 1.0, 0.5);
        }
        return player.getEyeLocation().add(player.getEyeLocation().getDirection().multiply(range));
    }

    public static Location getBlinkDestination(Player player, int distance) {
        Vector direction = player.getLocation().getDirection().setY(0).normalize();
        if (direction.lengthSquared() < 0.01) {
            direction = new Vector(0, 0, 1);
        }
        Location dest = player.getLocation().clone().add(direction.multiply(distance));
        dest.setY(player.getLocation().getY());
        return dest;
    }

    public static boolean isInsideArena(HungerGamesPlugin plugin, Player player, Location location) {
        return plugin.getGameManager().getPlayerGame(player).map(game -> {
            Arena arena = game.getArena();
            if (arena.getWorld() == null || !location.getWorld().equals(arena.getWorld())) {
                return false;
            }
            return CircleUtil.isInsideCircle(
                    arena.getCenterX(), arena.getCenterZ(),
                    location.getX(), location.getZ(),
                    arena.getRadius() - 2
            );
        }).orElse(false);
    }

    public static Game getActiveGame(HungerGamesPlugin plugin, Player player) {
        return plugin.getGameManager().getPlayerGame(player)
                .filter(game -> game.getState() == GameState.ACTIVE)
                .orElse(null);
    }

    public static boolean isSimulationGame(HungerGamesPlugin plugin, Player player) {
        return plugin.getGameManager().getPlayerGame(player)
                .map(Game::isSimulationMode)
                .orElse(false);
    }
}
