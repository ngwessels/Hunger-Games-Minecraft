package com.hungergames.powerup;

import org.bukkit.Material;
import org.bukkit.Particle;

import java.util.HashMap;
import java.util.Map;

public class PowerUpDefinition {

    private final String id;
    private String displayName;
    private Material material = Material.NETHER_STAR;
    private Particle particle = Particle.HAPPY_VILLAGER;
    private int weight = 1;
    private PowerUpEffectType effectType = PowerUpEffectType.POTION;
    private PowerUpSpawnRule spawnRule = PowerUpSpawnRule.NORMAL;
    private final Map<String, Object> params = new HashMap<>();

    public PowerUpDefinition(String id) {
        this.id = id;
    }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public Material getMaterial() {
        return material;
    }

    public void setMaterial(Material material) {
        this.material = material;
    }

    public Particle getParticle() {
        return particle;
    }

    public void setParticle(Particle particle) {
        this.particle = particle;
    }

    public int getWeight() {
        return weight;
    }

    public void setWeight(int weight) {
        this.weight = weight;
    }

    public PowerUpEffectType getEffectType() {
        return effectType;
    }

    public void setEffectType(PowerUpEffectType effectType) {
        this.effectType = effectType;
    }

    public PowerUpSpawnRule getSpawnRule() {
        return spawnRule;
    }

    public void setSpawnRule(PowerUpSpawnRule spawnRule) {
        this.spawnRule = spawnRule;
    }

    public Map<String, Object> getParams() {
        return params;
    }

    public int getInt(String key, int defaultValue) {
        Object value = params.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        return defaultValue;
    }

    public double getDouble(String key, double defaultValue) {
        Object value = params.get(key);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return defaultValue;
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        Object value = params.get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        if (value instanceof String str) {
            return Boolean.parseBoolean(str);
        }
        return defaultValue;
    }
}
