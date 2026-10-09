package com.hungergames.game;

import com.hungergames.HungerGamesPlugin;
import com.hungergames.arena.ArcticLayout;
import com.hungergames.arena.ArcticRing;
import com.hungergames.arena.Arena;
import com.hungergames.gamemode.ArcticConfig;
import com.hungergames.gamemode.GameMode;
import com.hungergames.gamemode.TerrainPhaseConfig;
import com.hungergames.terrain.VolcanoBiomeFactions;
import com.hungergames.util.ArenaEntityUtil;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.entity.PolarBear;

/**
 * Polar bears for volcano arctic rings. They wait until hostile spawning is
 * allowed (after FOREST). Faction-war volcano matches spawn ice units instead.
 */
public class ArcticManager {

    private final HungerGamesPlugin plugin;
    private boolean bearsSpawned;

    public ArcticManager(HungerGamesPlugin plugin) {
        this.plugin = plugin;
    }

    public void onGraceStart(Game game) {
        bearsSpawned = false;
        GameMode mode = game.getGameMode();
        Arena arena = game.getArena();
        if (!ArcticRing.isActive(arena, mode)) {
            return;
        }
        World world = arena.getWorld();
        if (world == null) {
            return;
        }
        ArcticRing.refreshLayout(arena, mode);
    }

    public void clear() {
        bearsSpawned = false;
    }

    /** Retarget bears that lost interest, including at close range. */
    public void tick(Game game) {
        GameMode mode = game.getGameMode();
        Arena arena = game.getArena();
        if (!ArcticRing.isActive(arena, mode)) {
            return;
        }
        if (game.getState() != GameState.GRACE && game.getState() != GameState.ACTIVE) {
            return;
        }
        World world = arena.getWorld();
        if (world == null) {
            return;
        }
        if (usesFactionWar(mode)) {
            return;
        }
        if (!plugin.getTerrainPhaseManager().allowsHostileSpawning(game)) {
            return;
        }
        ArcticConfig config = mode.getArctic();
        if (!bearsSpawned) {
            double follow = Math.max(24.0, config.getPolarBearAggroRange() * 1.5);
            for (ArcticLayout.Site site : arena.getArcticLayout().getPolarBearSites()) {
                spawnBear(world, site, follow);
            }
            bearsSpawned = true;
        }
        double range = config.getPolarBearAggroRange();
        double rangeSq = range * range;
        for (PolarBear bear : world.getEntitiesByClass(PolarBear.class)) {
            if (!ArenaEntityUtil.isArcticMob(bear) || bear.isDead()) {
                continue;
            }
            if (bear.getTarget() instanceof Player current
                    && current.isOnline() && !current.isDead()
                    && game.getAlivePlayers().contains(current)) {
                continue;
            }
            Player nearest = nearestAlive(game, bear.getLocation(), rangeSq);
            if (nearest != null) {
                bear.setTarget(nearest);
            }
        }
    }

    private void spawnBear(World world, ArcticLayout.Site site, double followRange) {
        Chunk chunk = world.getChunkAt(site.x() >> 4, site.z() >> 4);
        if (!chunk.isLoaded()) {
            chunk.load(true);
        }
        Location loc = new Location(world, site.x() + 0.5, site.y(), site.z() + 0.5);
        PolarBear bear = world.spawn(loc, PolarBear.class, entity -> {
            entity.setAdult();
            entity.setRemoveWhenFarAway(false);
            entity.setPersistent(true);
            AttributeInstance follow = entity.getAttribute(Attribute.FOLLOW_RANGE);
            if (follow != null) {
                follow.setBaseValue(followRange);
            }
        });
        ArenaEntityUtil.tagArcticMob(plugin, bear);
    }

    private static Player nearestAlive(Game game, Location from, double rangeSq) {
        Player best = null;
        double bestDist = rangeSq;
        for (Player player : game.getAlivePlayers()) {
            if (player == null || !player.isOnline() || player.isDead()) {
                continue;
            }
            if (player.getWorld() != from.getWorld()) {
                continue;
            }
            double dist = player.getLocation().distanceSquared(from);
            if (dist <= bestDist) {
                bestDist = dist;
                best = player;
            }
        }
        return best;
    }

    private static boolean usesFactionWar(GameMode mode) {
        if (mode == null) {
            return false;
        }
        for (TerrainPhaseConfig phase : mode.getTerrainPhases()) {
            if ("VOLCANO_ERUPTION".equalsIgnoreCase(phase.getType())
                    && VolcanoBiomeFactions.isEnabled(phase)) {
                return true;
            }
        }
        return false;
    }
}
