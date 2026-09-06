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
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

public class LootTable {

    private static final String LOOT_TABLE_VERSION_KEY = "version";
    private static final int LOOT_TABLE_VERSION = 2;

    private final HungerGamesPlugin plugin;
    private final Random random = new Random();
    private YamlConfiguration tablesConfig;
    private YamlConfiguration boxesConfig;

    public LootTable(HungerGamesPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        tablesConfig = loadLootYaml("loot/tables.yml");
        boxesConfig = loadLootYaml("loot/loot_boxes.yml");
    }

    /**
     * Loads a loot YAML, falling back to the packaged defaults when the file on disk
     * is missing, unreadable, or from an older loot schema. Older installs kept the
     * first version they were given forever (copy-if-missing), so a pre-conversion
     * file would silently roll zero items for every chest; those files are now backed
     * up and replaced so chests get loot again.
     */
    private YamlConfiguration loadLootYaml(String resourcePath) {
        File file = new File(plugin.getDataFolder(), resourcePath);
        boolean needsDefaultCopy = !file.exists();

        YamlConfiguration config = null;
        if (!needsDefaultCopy) {
            config = YamlConfiguration.loadConfiguration(file);
            if (isInvalidOrLegacy(config)) {
                File backup = new File(plugin.getDataFolder(), resourcePath + ".bak");
                try {
                    Files.copy(file.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    plugin.getLogger().warning(resourcePath + " was missing expected sections or used an "
                            + "outdated format; it has been replaced with defaults and backed up to "
                            + backup.getName() + ".");
                } catch (IOException e) {
                    plugin.getLogger().warning("Could not back up outdated " + resourcePath
                            + ": " + e.getMessage());
                }
                needsDefaultCopy = true;
            }
        }

        if (needsDefaultCopy) {
            if (!saveDefaultResource(resourcePath)) {
                plugin.getLogger().severe("Missing packaged default " + resourcePath
                        + "; loot rolls will be empty until it is restored.");
                return new YamlConfiguration();
            }
            config = YamlConfiguration.loadConfiguration(file);
        }

        warnIfIncomplete(config, resourcePath);
        return config;
    }

    /** True when the file cannot be parsed or predates the chest-era loot schema. */
    private boolean isInvalidOrLegacy(YamlConfiguration config) {
        if (config == null) {
            return true;
        }
        int version = config.getInt(LOOT_TABLE_VERSION_KEY, 0);
        if (version > 0) {
            return version < LOOT_TABLE_VERSION;
        }
        // Versionless files: treat them as legacy when they lack the current structure.
        boolean hasTiers = config.isConfigurationSection("tiers");
        boolean hasCornucopia = config.isConfigurationSection("cornucopia");
        return !hasTiers || !hasCornucopia;
    }

    private void warnIfIncomplete(YamlConfiguration config, String resourcePath) {
        Set<String> requiredTiers = Set.of("easy", "normal", "hard", "extreme");
        for (String tier : requiredTiers) {
            if (config.getConfigurationSection("tiers." + tier + ".pools") == null) {
                plugin.getLogger().warning(resourcePath + " is missing tiers." + tier
                        + ".pools; scattered loot chests will be empty for that tier.");
            }
            if (config.getConfigurationSection("cornucopia." + tier + ".pools") == null) {
                plugin.getLogger().warning(resourcePath + " is missing cornucopia." + tier
                        + ".pools; center chests will be empty for that tier.");
            }
        }
        for (String pool : Set.of("weapons", "armor", "potions", "utility", "custom")) {
            if (!config.contains("pools." + pool)) {
                plugin.getLogger().warning(resourcePath + " is missing pools." + pool
                        + "; that pool will always fall back to bread.");
            }
            for (String tier : requiredTiers) {
                if (config.getMapList("pools." + pool + "." + tier).isEmpty()
                        && config.getMapList("pools." + pool + ".normal").isEmpty()) {
                    plugin.getLogger().warning(resourcePath + " pool '" + pool + "' has no entries for tier '"
                            + tier + "' (and no normal fallback).");
                }
                if ("custom".equals(pool)
                        && config.getMapList("pools.custom." + tier).isEmpty()
                        && config.getMapList("pools.custom.normal").isEmpty()) {
                    plugin.getLogger().warning(resourcePath + " custom item pool is empty for tier '"
                            + tier + "'. Custom items will not appear in loot.");
                }
            }
        }
    }

    private boolean saveDefaultResource(String resourcePath) {
        try (InputStream in = plugin.getResource(resourcePath)) {
            if (in == null) {
                return false;
            }
            File file = new File(plugin.getDataFolder(), resourcePath);
            file.getParentFile().mkdirs();
            Files.copy(in, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to write default " + resourcePath + ": " + e.getMessage());
            return false;
        }
    }

    public List<ItemStack> roll(String tierKey, boolean cornucopia) {
        String section = cornucopia ? "cornucopia" : "tiers";
        String tier = tierKey == null || tierKey.isBlank() ? "normal" : tierKey.toLowerCase();

        List<ItemStack> results = new ArrayList<>();
        String configPath = section + "." + tier;
        int rolls = boxesConfig.getInt(configPath + ".rolls", 2);
        ConfigurationSection pools = boxesConfig.getConfigurationSection(configPath + ".pools");

        if (pools == null) {
            plugin.getLogger().warning("No loot pools found at loot_boxes.yml:" + configPath
                    + "; trying the normal tier.");
            pools = boxesConfig.getConfigurationSection(section + ".normal.pools");
            tier = "normal";
        }
        if (pools != null) {
            for (int i = 0; i < rolls; i++) {
                String pool = pickWeightedPool(pools);
                ItemStack item = rollFromPool(pool, tier);
                if (item != null) {
                    results.add(item);
                }
            }
        }

        if (results.isEmpty() && !cornucopia) {
            plugin.getLogger().warning("Loot roll produced no items for tier '" + tierKey
                    + "'. Falling back to the normal-tier table.");
            results = rollFallbackNormal(cornucopia);
        }
        if (results.isEmpty() && !cornucopia) {
            plugin.getLogger().severe("Even the normal-tier loot roll failed; placing guaranteed "
                    + "basic gear instead of an empty chest.");
            results = buildEmergencyLoot(tierKey);
        }
        return results;
    }

    private List<ItemStack> rollFallbackNormal(boolean cornucopia) {
        List<ItemStack> results = new ArrayList<>();
        int rolls = boxesConfig.getInt("tiers.normal.rolls", 2);
        ConfigurationSection pools = boxesConfig.getConfigurationSection("tiers.normal.pools");
        if (pools == null) {
            return results;
        }
        for (int i = 0; i < rolls; i++) {
            String pool = pickWeightedPool(pools);
            ItemStack item = rollFromPool(pool, "normal");
            if (item != null) {
                results.add(item);
            }
        }
        return results;
    }

    /** Last-resort loot so players never find a completely empty chest. */
    private List<ItemStack> buildEmergencyLoot(String tierKey) {
        List<ItemStack> results = new ArrayList<>();
        results.add(new ItemStack(Material.STONE_SWORD));
        results.add(new ItemStack(Material.COOKED_BEEF, 8));

        ItemStack helmet = switch (tierKey == null ? "" : tierKey.toLowerCase()) {
            case "hard", "extreme" -> new ItemStack(Material.IRON_HELMET);
            default -> new ItemStack(Material.LEATHER_HELMET);
        };
        results.add(helmet);
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
            plugin.getLogger().warning("Unknown material '" + materialName + "' in tables.yml pool '"
                    + pool + "'; substituting bread.");
            return new ItemStack(Material.BREAD);
        }

        int amount = entry.containsKey("amount") ? ((Number) entry.get("amount")).intValue() : 1;
        ItemStack stack = new ItemStack(material, Math.max(1, amount));

        if (entry.containsKey("enchantments") && entry.get("enchantments") instanceof Map<?, ?> enchants) {
            for (Map.Entry<?, ?> enchant : enchants.entrySet()) {
                String enchantKey = String.valueOf(enchant.getKey()).toLowerCase();
                Enchantment enchantment = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(enchantKey));
                if (enchantment != null) {
                    stack.addUnsafeEnchantment(enchantment, ((Number) enchant.getValue()).intValue());
                } else {
                    plugin.getLogger().warning("Unknown enchantment '" + enchantKey
                            + "' in tables.yml pool '" + pool + "'.");
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
            plugin.getLogger().warning("Custom item pool empty for tier '" + tier
                    + "'; skipping custom loot this roll.");
            return null;
        }
        Map<?, ?> entry = entries.get(random.nextInt(entries.size()));
        Object rawId = entry.get("customId");
        if (rawId == null) {
            return null;
        }
        String customId = String.valueOf(rawId);
        CustomItemRegistry registry = plugin.getCustomItemRegistry();
        if (!registry.isRegistered(customId)) {
            plugin.getLogger().warning("Unknown custom item id '" + customId
                    + "' in tables.yml pools.custom; skipping it.");
            return null;
        }
        return registry.createItem(customId).orElse(null);
    }
}
