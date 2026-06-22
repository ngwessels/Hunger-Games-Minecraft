package com.hungergames.item;

import org.bukkit.Material;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CustomItemDefinition {

    private final String id;
    private String displayName;
    private Material material;
    private List<String> lore;
    private CustomItemType type;
    private AbilityType ability;
    private final Map<String, Object> params = new HashMap<>();

    public CustomItemDefinition(String id) {
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

    public List<String> getLore() {
        return lore;
    }

    public void setLore(List<String> lore) {
        this.lore = lore;
    }

    public CustomItemType getType() {
        return type;
    }

    public void setType(CustomItemType type) {
        this.type = type;
    }

    public AbilityType getAbility() {
        return ability;
    }

    public void setAbility(AbilityType ability) {
        this.ability = ability;
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
}
