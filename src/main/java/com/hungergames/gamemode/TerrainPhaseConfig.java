package com.hungergames.gamemode;

import java.util.HashMap;
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
}
