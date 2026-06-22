package com.hungergames.loot;

import com.hungergames.HungerGamesPlugin;
import com.hungergames.item.CustomItemRegistry;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionType;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

public class LootTable {

    private final HungerGamesPlugin plugin;
    private final Random random = new Random();
    private YamlConfiguration tablesConfig;
    private YamlConfiguration boxesConfig;

    public LootTable(HungerGamesPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        copyDefaultIfMissing("loot/tables.yml");
        copyDefaultIfMissing("loot/loot_boxes.yml");
        tablesConfig = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "loot/tables.yml"));
        boxesConfig = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "loot/loot_boxes.yml"));
    }

    private void copyDefaultIfMissing(String path) {
        File file = new File(plugin.getDataFolder(), path);
        if (!file.exists()) {
            file.getParentFile().mkdirs();
            plugin.saveResource(path, false);
        }
    }

    public List<ItemStack> roll(String tierKey, boolean cornucopia) {
        String configPath = cornucopia ? "cornucopia." + tierKey : "tiers." + tierKey;
        int rolls = boxesConfig.getInt(configPath + ".rolls", 2);
        ConfigurationSection pools = boxesConfig.getConfigurationSection(configPath + ".pools");
        if (pools == null) {
            return List.of();
        }

        List<ItemStack> results = new ArrayList<>();
        for (int i = 0; i < rolls; i++) {
            String pool = pickWeightedPool(pools);
            ItemStack item = rollFromPool(pool, tierKey);
            if (item != null) {
                results.add(item);
            }
        }
        return results;
    }

    private String pickWeightedPool(ConfigurationSection pools) {
        int total = 0;
        for (String key : pools.getKeys(false)) {
            total += pools.getInt(key);
        }
        if (total <= 0) {
            return pools.getKeys(false).iterator().next();
        }
        int roll = random.nextInt(total);
        int cumulative = 0;
        for (String key : pools.getKeys(false)) {
            cumulative += pools.getInt(key);
            if (roll < cumulative) {
                return key;
            }
        }
        return "utility";
    }

    private ItemStack rollFromPool(String pool, String tier) {
        if ("custom".equals(pool)) {
            return rollCustomItem(tier);
        }

        List<Map<?, ?>> entries = tablesConfig.getMapList("pools." + pool + "." + tier);
        if (entries.isEmpty()) {
            entries = tablesConfig.getMapList("pools." + pool + ".normal");
        }
        if (entries.isEmpty()) {
            return new ItemStack(Material.BREAD);
        }

        Map<?, ?> entry = entries.get(random.nextInt(entries.size()));
        String materialName = String.valueOf(entry.get("material"));
        Material material = Material.matchMaterial(materialName);
        if (material == null) {
            return new ItemStack(Material.BREAD);
        }

        int amount = entry.containsKey("amount") ? ((Number) entry.get("amount")).intValue() : 1;
        ItemStack stack = new ItemStack(material, amount);

        if (entry.containsKey("enchantments") && entry.get("enchantments") instanceof Map<?, ?> enchants) {
            for (Map.Entry<?, ?> enchant : enchants.entrySet()) {
                String enchantKey = String.valueOf(enchant.getKey()).toLowerCase();
                Enchantment enchantment = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(enchantKey));
                if (enchantment != null) {
                    stack.addUnsafeEnchantment(enchantment, ((Number) enchant.getValue()).intValue());
                }
            }
        }

        if (material == Material.POTION || material == Material.SPLASH_POTION) {
            if (entry.containsKey("potion")) {
                PotionType type = PotionType.valueOf(String.valueOf(entry.get("potion")));
                PotionMeta meta = (PotionMeta) stack.getItemMeta();
                if (meta != null) {
                    meta.setBasePotionType(type);
                    stack.setItemMeta(meta);
                }
            }
        }

        return stack;
    }

    private ItemStack rollCustomItem(String tier) {
        List<Map<?, ?>> entries = tablesConfig.getMapList("pools.custom." + tier);
        if (entries.isEmpty()) {
            entries = tablesConfig.getMapList("pools.custom.normal");
        }
        if (entries.isEmpty()) {
            return null;
        }
        Map<?, ?> entry = entries.get(random.nextInt(entries.size()));
        String customId = String.valueOf(entry.get("customId"));
        CustomItemRegistry registry = plugin.getCustomItemRegistry();
        return registry.createItem(customId).orElse(null);
    }
}
