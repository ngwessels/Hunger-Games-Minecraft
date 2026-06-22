package com.hungergames.terrain;

import com.hungergames.arena.Arena;
import com.hungergames.game.Game;
import com.hungergames.gamemode.TerrainPhaseConfig;
import com.hungergames.util.CircleUtil;
import com.hungergames.util.ParticleUtil;
import com.hungergames.util.SpawnLocationUtil;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Random;

public final class StormLightningUtil {

    private StormLightningUtil() {
    }

    public static void tick(Game game, Arena arena, TerrainPhaseConfig config, double intensity,
                            long[] lastStrikeMs, Random random) {
        double minIntensity = config.getDouble("lightningMinIntensity", 0.15);
        if (intensity < minIntensity) {
            return;
        }

        World world = arena.getWorld();
        if (world == null) {
            return;
        }

        long now = System.currentTimeMillis();
        long minInterval = config.getInt("lightningMinIntervalMs", 8000);
        long maxInterval = config.getInt("lightningMaxIntervalMs", 25000);
        long interval = (long) (maxInterval - (maxInterval - minInterval) * intensity);
        if (now - lastStrikeMs[0] < interval) {
            return;
        }
        if (random.nextDouble() > 0.2 + intensity * 0.6) {
            return;
        }

        List<Player> alive = game.getAlivePlayers();
        if (alive.isEmpty()) {
            return;
        }

        Player target = alive.get(random.nextInt(alive.size()));
        Location base = target.getLocation().clone();
        double offsetX = (random.nextDouble() - 0.5) * 8;
        double offsetZ = (random.nextDouble() - 0.5) * 8;
        Location strike = new Location(world,
                base.getX() + offsetX,
                base.getY(),
                base.getZ() + offsetZ);

        double cx = arena.getCenterX();
        double cz = arena.getCenterZ();
        if (!CircleUtil.isInsideCircle(cx, cz, strike.getX(), strike.getZ(), arena.getRadius() - 2)) {
            strike = CircleUtil.clampToCircle(cx, cz, strike, arena.getRadius() - 2);
        }
        strike.setY(SpawnLocationUtil.findGroundY(world, strike.getBlockX(), strike.getBlockZ(),
                (int) arena.getCenterY()));

        lastStrikeMs[0] = now;
        ParticleUtil.spawn(world, Particle.ELECTRIC_SPARK, strike,
                8 + (int) (intensity * 12), 0.5, 1.0, 0.5, 0.05);
        world.playSound(strike, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.5f + (float) intensity * 0.8f, 0.85f);
        world.playSound(strike, Sound.ENTITY_LIGHTNING_BOLT_IMPACT, 0.4f + (float) intensity * 0.5f, 1.0f);

        double realThreshold = config.getDouble("lightningRealStrikeThreshold", 0.55);
        if (intensity >= realThreshold) {
            world.strikeLightning(strike);
        } else {
            world.strikeLightningEffect(strike);
        }
    }
}
