package com.hungergames.item;

import com.hungergames.HungerGamesPlugin;
import com.hungergames.item.abilities.BlinkAbility;
import com.hungergames.item.abilities.BlackHoleAbility;
import com.hungergames.item.abilities.EggMachineGunAbility;
import com.hungergames.item.abilities.ExtraHealthAbility;
import com.hungergames.item.abilities.RedShellAbility;
import com.hungergames.item.abilities.FireResistanceAbility;
import com.hungergames.item.abilities.GhastBlastAbility;
import com.hungergames.item.abilities.IceSnareAbility;
import com.hungergames.item.abilities.InvisibilityBurstAbility;
import com.hungergames.item.abilities.ItemMagnetAbility;
import com.hungergames.item.abilities.JumpBoostAbility;
import com.hungergames.item.abilities.LifeStealAbility;
import com.hungergames.item.abilities.LightningStrikeAbility;
import com.hungergames.item.abilities.RegenAuraAbility;
import com.hungergames.item.abilities.ShockwaveAbility;
import com.hungergames.item.abilities.SpeedBurstAbility;
import com.hungergames.item.abilities.StrengthAuraAbility;
import com.hungergames.item.abilities.ThornsAuraAbility;
import com.hungergames.util.ColorUtil;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class CustomItemRegistry {

    public static final NamespacedKey CUSTOM_ITEM_KEY = new NamespacedKey("hungergames", "custom_item_id");
    public static final NamespacedKey USES_REMAINING_KEY = new NamespacedKey("hungergames", "uses_remaining");
    public static final NamespacedKey PROJECTILE_TYPE_KEY = new NamespacedKey("hungergames", "projectile_type");
    public static final NamespacedKey PROJECTILE_OWNER_KEY = new NamespacedKey("hungergames", "projectile_owner");
    public static final NamespacedKey PROJECTILE_DAMAGE_KEY = new NamespacedKey("hungergames", "projectile_damage");

    private final HungerGamesPlugin plugin;
    private final Map<String, CustomItemDefinition> definitions = new HashMap<>();
    private final Map<AbilityType, CustomItemAbility> abilities = new EnumMap<>(AbilityType.class);
    private final AbilityCooldownManager cooldownManager = new AbilityCooldownManager();
    private AbilityUseGuard useGuard;
    private EggMachineGunAbility eggMachineGunAbility;

    public CustomItemRegistry(HungerGamesPlugin plugin) {
        this.plugin = plugin;
        useGuard = new AbilityUseGuard(plugin, this, cooldownManager);
        registerAbilities();
        reload();
    }

    private void registerAbilities() {
        eggMachineGunAbility = new EggMachineGunAbility(plugin, useGuard);
        abilities.put(AbilityType.JUMP_BOOST, new JumpBoostAbility());
        abilities.put(AbilityType.EXTRA_HEALTH, new ExtraHealthAbility());
        abilities.put(AbilityType.GHAST_BLAST, new GhastBlastAbility(plugin, useGuard));
        abilities.put(AbilityType.BLACK_HOLE, new BlackHoleAbility(plugin, useGuard));
        abilities.put(AbilityType.SPEED_BURST, new SpeedBurstAbility(plugin, useGuard));
        abilities.put(AbilityType.REGEN_AURA, new RegenAuraAbility());
        abilities.put(AbilityType.LIGHTNING_STRIKE, new LightningStrikeAbility(plugin, useGuard));
        abilities.put(AbilityType.BLINK, new BlinkAbility(plugin, useGuard));
        abilities.put(AbilityType.SHOCKWAVE, new ShockwaveAbility(plugin, useGuard));
        abilities.put(AbilityType.INVISIBILITY_BURST, new InvisibilityBurstAbility(plugin, useGuard));
        abilities.put(AbilityType.STRENGTH_AURA, new StrengthAuraAbility());
        abilities.put(AbilityType.FIRE_RESISTANCE, new FireResistanceAbility());
        abilities.put(AbilityType.ICE_SNARE, new IceSnareAbility(plugin, useGuard));
        abilities.put(AbilityType.LIFE_STEAL, new LifeStealAbility(plugin));
        abilities.put(AbilityType.THORNS_AURA, new ThornsAuraAbility(plugin));
        abilities.put(AbilityType.ITEM_MAGNET, new ItemMagnetAbility(plugin, useGuard));
        abilities.put(AbilityType.RED_SHELL, new RedShellAbility(plugin, useGuard));
        abilities.put(AbilityType.EGG_MACHINE_GUN, eggMachineGunAbility);
    }

    public void reload() {
        definitions.clear();
        File file = new File(plugin.getDataFolder(), "custom_items.yml");
        if (!file.exists()) {
            plugin.saveResource("custom_items.yml", false);
        }
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection items = config.getConfigurationSection("items");
        if (items == null) {
            return;
        }
        for (String id : items.getKeys(false)) {
            ConfigurationSection section = items.getConfigurationSection(id);
            if (section == null) {
                continue;
            }
            CustomItemDefinition def = new CustomItemDefinition(id);
            def.setDisplayName(section.getString("displayName", id));
            Material material = Material.matchMaterial(section.getString("material", "STONE"));
            def.setMaterial(material != null ? material : Material.STONE);
            def.setLore(section.getStringList("lore"));
            try {
                def.setType(CustomItemType.valueOf(section.getString("type", "ACTIVE").toUpperCase()));
            } catch (IllegalArgumentException e) {
                def.setType(CustomItemType.ACTIVE);
            }
            try {
                def.setAbility(AbilityType.valueOf(section.getString("ability", "SPEED_BURST").toUpperCase()));
            } catch (IllegalArgumentException e) {
                def.setAbility(AbilityType.SPEED_BURST);
            }
            ConfigurationSection params = section.getConfigurationSection("params");
            if (params != null) {
                for (String key : params.getKeys(false)) {
                    def.getParams().put(key, params.get(key));
                }
            }
            definitions.put(id.toLowerCase(), def);
        }
        plugin.getLogger().info("Loaded " + definitions.size() + " custom items.");
    }

    public Optional<CustomItemDefinition> getDefinition(String id) {
        return Optional.ofNullable(definitions.get(id.toLowerCase()));
    }

    public Optional<ItemStack> createItem(String id) {
        return getDefinition(id).map(this::buildItem);
    }

    public ItemStack buildItem(CustomItemDefinition def) {
        ItemStack stack = new ItemStack(def.getMaterial());
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ColorUtil.color(def.getDisplayName()));
            int maxUses = def.getInt("maxUses", 0);
            if (maxUses > 0) {
                meta.getPersistentDataContainer().set(USES_REMAINING_KEY, PersistentDataType.INTEGER, maxUses);
            }
            meta.setLore(buildLore(def, maxUses > 0 ? maxUses : -1));
            meta.getPersistentDataContainer().set(CUSTOM_ITEM_KEY, PersistentDataType.STRING, def.getId());
            stack.setItemMeta(meta);
        }
        return stack;
    }

    public void refreshItemLore(ItemStack stack, CustomItemDefinition def, int remainingUses) {
        if (stack == null || !stack.hasItemMeta()) {
            return;
        }
        ItemMeta meta = stack.getItemMeta();
        meta.setLore(buildLore(def, remainingUses));
        stack.setItemMeta(meta);
    }

    private List<String> buildLore(CustomItemDefinition def, int remainingUses) {
        List<String> lore = new ArrayList<>(def.getLore());
        if (remainingUses >= 0) {
            lore.add("&8Uses: &f" + remainingUses);
        }
        return ColorUtil.color(lore);
    }

    public Optional<CustomItemDefinition> getFromItem(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) {
            return Optional.empty();
        }
        String id = stack.getItemMeta().getPersistentDataContainer()
                .get(CUSTOM_ITEM_KEY, PersistentDataType.STRING);
        if (id == null) {
            return Optional.empty();
        }
        return getDefinition(id);
    }

    public Optional<CustomItemAbility> getAbility(AbilityType type) {
        return Optional.ofNullable(abilities.get(type));
    }

    public AbilityCooldownManager getCooldownManager() {
        return cooldownManager;
    }

    public AbilityUseGuard getUseGuard() {
        return useGuard;
    }

    public EggMachineGunAbility getEggMachineGunAbility() {
        return eggMachineGunAbility;
    }

    public Collection<CustomItemDefinition> getDefinitions() {
        return Collections.unmodifiableCollection(definitions.values());
    }

    public void grantAllTestItems(Player player) {
        player.getInventory().clear();
        player.getInventory().setArmorContents(new ItemStack[4]);
        player.getInventory().setItemInOffHand(null);

        List<CustomItemDefinition> sorted = new ArrayList<>(definitions.values());
        sorted.sort(Comparator.comparing(CustomItemDefinition::getId));

        for (CustomItemDefinition def : sorted) {
            ItemStack item = buildItem(def);
            if (def.getType() == CustomItemType.PASSIVE_ARMOR) {
                equipArmor(player, item);
            } else {
                addToInventoryOrDrop(player, item);
            }
        }
    }

    private void equipArmor(Player player, ItemStack item) {
        EquipmentSlot slot = item.getType().getEquipmentSlot();
        if (slot == EquipmentSlot.HAND || slot == EquipmentSlot.OFF_HAND) {
            addToInventoryOrDrop(player, item);
            return;
        }
        ItemStack existing = player.getInventory().getItem(slot);
        if (existing == null || existing.getType().isAir()) {
            player.getInventory().setItem(slot, item);
        } else {
            addToInventoryOrDrop(player, item);
        }
    }

    private void addToInventoryOrDrop(Player player, ItemStack item) {
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(item);
        leftover.values().forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
    }

    public void stripFromPlayers(List<Player> players) {
        for (Player player : players) {
            for (int i = 0; i < player.getInventory().getSize(); i++) {
                ItemStack item = player.getInventory().getItem(i);
                if (getFromItem(item).isPresent()) {
                    player.getInventory().setItem(i, null);
                }
            }
            ItemStack[] armor = player.getInventory().getArmorContents();
            for (int i = 0; i < armor.length; i++) {
                if (getFromItem(armor[i]).isPresent()) {
                    armor[i] = null;
                }
            }
            player.getInventory().setArmorContents(armor);
            cooldownManager.clear(player);
            if (eggMachineGunAbility != null) {
                eggMachineGunAbility.stopFiring(player);
            }
            for (CustomItemAbility ability : abilities.values()) {
                for (CustomItemDefinition def : definitions.values()) {
                    if (def.getAbility() == ability.type()) {
                        ability.removePassive(player, def);
                    }
                }
            }
        }
    }
}
