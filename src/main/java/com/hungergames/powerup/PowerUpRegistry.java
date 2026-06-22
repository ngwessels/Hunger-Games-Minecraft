package com.hungergames.powerup;

import com.hungergames.HungerGamesPlugin;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

public class PowerUpRegistry {

    private final HungerGamesPlugin plugin;
    private final Map<String, PowerUpDefinition> definitions = new HashMap<>();
    private final Random random = new Random();

    public PowerUpRegistry(HungerGamesPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        definitions.clear();
        File file = new File(plugin.getDataFolder(), "powerups.yml");
        if (!file.exists()) {
            plugin.saveResource("powerups.yml", false);
        }
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection powerups = config.getConfigurationSection("powerups");
        if (powerups == null) {
            return;
        }

        int minDuration = plugin.getPluginConfig().getPowerUpMinEffectDurationSeconds();
        for (String id : powerups.getKeys(false)) {
            ConfigurationSection section = powerups.getConfigurationSection(id);
            if (section == null) {
                continue;
            }
            PowerUpDefinition def = new PowerUpDefinition(id);
            def.setDisplayName(section.getString("displayName", id));
            Material material = Material.matchMaterial(section.getString("material", "NETHER_STAR"));
            if (material != null) {
                def.setMaterial(material);
            }
            String particleName = section.getString("particle", "HAPPY_VILLAGER");
            try {
                def.setParticle(Particle.valueOf(particleName.toUpperCase()));
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("Unknown particle for power-up " + id + ": " + particleName);
            }
            def.setWeight(Math.max(1, section.getInt("weight", 1)));
            try {
                def.setEffectType(PowerUpEffectType.valueOf(section.getString("effect", "POTION").toUpperCase()));
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("Unknown effect for power-up " + id);
            }
            def.setSpawnRule(PowerUpSpawnRule.fromString(section.getString("spawnRule", "NORMAL")));
            ConfigurationSection params = section.getConfigurationSection("params");
            if (params != null) {
                for (String key : params.getKeys(false)) {
                    def.getParams().put(key, params.get(key));
                }
            }
            clampTimedDuration(def, minDuration);
            definitions.put(id.toLowerCase(), def);
        }
    }

    private void clampTimedDuration(PowerUpDefinition def, int minDuration) {
        if (def.getEffectType() == PowerUpEffectType.HEAL
                || def.getEffectType() == PowerUpEffectType.GUARDIAN_CONTROL) {
            return;
        }
        int duration = def.getInt("durationSeconds", minDuration);
        if (duration < minDuration) {
            plugin.getLogger().warning("Power-up " + def.getId() + " durationSeconds " + duration
                    + " below minimum " + minDuration + "; clamping.");
            def.getParams().put("durationSeconds", minDuration);
        }
    }

    public Collection<PowerUpDefinition> getDefinitions() {
        return Collections.unmodifiableCollection(definitions.values());
    }

    public Optional<PowerUpDefinition> getDefinition(String id) {
        return Optional.ofNullable(definitions.get(id.toLowerCase()));
    }

    public Optional<PowerUpDefinition> rollNormal() {
        return roll(def -> def.getSpawnRule() == PowerUpSpawnRule.NORMAL);
    }

    public Optional<PowerUpDefinition> rollById(String id) {
        return getDefinition(id);
    }

    public Optional<PowerUpDefinition> roll(PowerUpSpawnRule rule) {
        return roll(def -> def.getSpawnRule() == rule);
    }

    private Optional<PowerUpDefinition> roll(java.util.function.Predicate<PowerUpDefinition> filter) {
        List<PowerUpDefinition> pool = new ArrayList<>();
        int totalWeight = 0;
        for (PowerUpDefinition def : definitions.values()) {
            if (!filter.test(def)) {
                continue;
            }
            pool.add(def);
            totalWeight += def.getWeight();
        }
        if (pool.isEmpty() || totalWeight <= 0) {
            return Optional.empty();
        }
        int roll = random.nextInt(totalWeight);
        int cumulative = 0;
        for (PowerUpDefinition def : pool) {
            cumulative += def.getWeight();
            if (roll < cumulative) {
                return Optional.of(def);
            }
        }
        return Optional.of(pool.getLast());
    }

    public boolean apply(Player player, PowerUpDefinition definition) {
        return switch (definition.getEffectType()) {
            case POTION -> applyPotion(player, definition);
            case HEAL -> applyHeal(player, definition);
            case ABSORPTION -> applyAbsorption(player, definition);
            case GUARDIAN_CONTROL -> false;
        };
    }

    private boolean applyPotion(Player player, PowerUpDefinition definition) {
        String typeName = String.valueOf(definition.getParams().getOrDefault("potionType", "SPEED"));
        PotionEffectType type = PotionEffectType.getByName(typeName.toUpperCase());
        if (type == null) {
            plugin.getLogger().warning("Unknown potion type for power-up " + definition.getId() + ": " + typeName);
            return false;
        }
        int durationTicks = definition.getInt("durationSeconds", 20) * 20;
        int amplifier = Math.max(0, definition.getInt("amplifier", 1) - 1);
        player.addPotionEffect(new PotionEffect(type, durationTicks, amplifier));
        return true;
    }

    private boolean applyHeal(Player player, PowerUpDefinition definition) {
        double amount = definition.getInt("amount", 4);
        double newHealth = Math.min(player.getMaxHealth(), player.getHealth() + amount);
        player.setHealth(newHealth);
        return true;
    }

    private boolean applyAbsorption(Player player, PowerUpDefinition definition) {
        PotionEffectType absorption = PotionEffectType.ABSORPTION;
        int durationTicks = definition.getInt("durationSeconds", 30) * 20;
        int amplifier = Math.max(0, definition.getInt("hearts", 4) - 1);
        player.addPotionEffect(new PotionEffect(absorption, durationTicks, amplifier));
        return true;
    }
}
