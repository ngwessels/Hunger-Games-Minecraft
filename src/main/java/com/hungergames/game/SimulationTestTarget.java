package com.hungergames.game;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Zombie;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

public final class SimulationTestTarget {

    public static final NamespacedKey TARGET_KEY = new NamespacedKey("hungergames", "simulation_test_target");
    private static final double MAX_HEALTH = 200.0;

    private SimulationTestTarget() {
    }

    public static LivingEntity spawn(Game game, Location near) {
        if (near == null || near.getWorld() == null) {
            return null;
        }

        Location spawnLoc = near.clone().add(new Vector(3, 0, 0));
        spawnLoc.setY(near.getWorld().getHighestBlockYAt(spawnLoc) + 1);

        Zombie zombie = near.getWorld().spawn(spawnLoc, Zombie.class, entity -> {
            entity.setCustomName("Test Target");
            entity.setCustomNameVisible(true);
            entity.setRemoveWhenFarAway(false);
            entity.setPersistent(true);
            entity.getPersistentDataContainer().set(
                    TARGET_KEY,
                    PersistentDataType.STRING,
                    game.getArena().getName().toLowerCase()
            );
            var maxHealth = entity.getAttribute(Attribute.GENERIC_MAX_HEALTH);
            if (maxHealth != null) {
                maxHealth.setBaseValue(MAX_HEALTH);
            }
            entity.setHealth(MAX_HEALTH);
        });
        return zombie;
    }

    public static void despawnAll(Game game) {
        var world = game.getArena().getWorld();
        if (world == null) {
            return;
        }
        String arenaKey = game.getArena().getName().toLowerCase();
        for (Entity entity : world.getEntities()) {
            if (!(entity instanceof LivingEntity)) {
                continue;
            }
            String tagged = entity.getPersistentDataContainer().get(TARGET_KEY, PersistentDataType.STRING);
            if (arenaKey.equals(tagged)) {
                entity.remove();
            }
        }
    }
}
