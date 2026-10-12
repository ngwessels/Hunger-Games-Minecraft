package com.hungergames.gamemode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TerrainPhaseConfig {

    private final String type;
    private final int durationSeconds;
    private final Map<String, Object> params;

    public TerrainPhaseConfig(String type, int durationSeconds, Map<String, Object> params) {
        this.type = type;
        this.durationSeconds = durationSeconds;
        this.params = params != null ? params : new HashMap<>();
    }

    public String getType() {
        return type;
    }

    public int getDurationSeconds() {
        return durationSeconds;
    }

    public Map<String, Object> getParams() {
        return params;
    }

    public String getString(String key, String defaultValue) {
        Object value = params.get(key);
        return value != null ? value.toString() : defaultValue;
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
        if (value instanceof String raw) {
            if ("true".equalsIgnoreCase(raw) || "yes".equalsIgnoreCase(raw) || "1".equals(raw)) {
                return true;
            }
            if ("false".equalsIgnoreCase(raw) || "no".equalsIgnoreCase(raw) || "0".equals(raw)) {
                return false;
            }
        }
        return defaultValue;
    }

    public List<String> getStringList(String key) {
        Object raw = params.get(key);
        List<String> result = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item != null) {
                    result.add(String.valueOf(item));
                }
            }
        }
        return result;
    }
}
