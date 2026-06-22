package com.hungergames.util;

import com.hungergames.arena.Arena;
import com.hungergames.game.Game;
import com.hungergames.game.PlayerSession;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Assigns evenly spaced spawn anchors around the arena ring with a random rotation,
 * then shuffles which player receives each slot.
 */
public final class SpawnAssignmentUtil {

    private SpawnAssignmentUtil() {
    }

    public static void assignMatchSpawns(Game game, List<Player> players) {
        if (players.isEmpty()) {
            return;
        }

        Arena arena = game.getArena();
        double spawnRadius = arena.getRadius() * 0.8;
        double centerX = arena.getCenterX();
        double centerZ = arena.getCenterZ();
        int playerCount = players.size();
        double randomOffset = ThreadLocalRandom.current().nextDouble() * 2 * Math.PI;

        List<Player> shuffled = new ArrayList<>(players);
        Collections.shuffle(shuffled);

        for (int slot = 0; slot < playerCount; slot++) {
            Player player = shuffled.get(slot);
            PlayerSession session = game.getSession(player.getUniqueId());
            if (session == null) {
                continue;
            }

            double angle = randomOffset + (2 * Math.PI * slot) / playerCount;
            int anchorX = (int) Math.round(centerX + Math.cos(angle) * spawnRadius);
            int anchorZ = (int) Math.round(centerZ + Math.sin(angle) * spawnRadius);
            session.setSpawnAnchor(anchorX, anchorZ);
        }
    }
}
