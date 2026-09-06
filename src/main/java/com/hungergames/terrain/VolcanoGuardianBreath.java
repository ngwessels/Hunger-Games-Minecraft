package com.hungergames.terrain;

import com.hungergames.game.Game;
import com.hungergames.game.PlayerSession;
import com.hungergames.gamemode.TerrainPhaseConfig;
import com.hungergames.util.ParticleUtil;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.Random;

public final class VolcanoGuardianBreath {

    private static final Random RANDOM = new Random();

    private VolcanoGuardianBreath() {
    }

    public static void fire(
            Game game,
            World world,
            Location breathCenter,
            TerrainPhaseConfig config,
            double guardianScale,
            Player exclude) {
        double breathDamage = config.getDouble("volcanoGuardianBreathDamage", 5.0);
        double breathRadius = config.getDouble("volcanoGuardianBreathRadius", 4.0) * guardianScale;

        world.playSound(breathCenter, Sound.ENTITY_ENDER_DRAGON_SHOOT, 1.5f, 0.8f);
        for (int i = 0; i < 30; i++) {
            double ox = (RANDOM.nextDouble() - 0.5) * breathRadius * 2;
            double oy = RANDOM.nextDouble() * 2;
            double oz = (RANDOM.nextDouble() - 0.5) * breathRadius * 2;
            ParticleUtil.spawn(world, Particle.DRAGON_BREATH,
                    breathCenter.getX() + ox, breathCenter.getY() + oy, breathCenter.getZ() + oz,
                    3, 0.2, 0.2, 0.2, 0.01);
        }

        for (Player player : game.getAlivePlayers()) {
            if (player.equals(exclude) || !player.getWorld().equals(world)) {
                continue;
            }
            PlayerSession session = game.getSession(player.getUniqueId());
            if (player.isDead() || (session != null && session.isInRespawnGrace())) {
                continue;
            }
            if (player.getLocation().distance(breathCenter) > breathRadius + 1.5) {
                continue;
            }
            player.damage(breathDamage);
            player.setFireTicks(Math.max(player.getFireTicks(), 80));
        }
    }
}
