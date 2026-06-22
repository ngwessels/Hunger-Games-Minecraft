package com.hungergames.powerup;

public enum PowerUpSpawnRule {
    NORMAL,
    GUARDIAN_ACTIVE;

    public static PowerUpSpawnRule fromString(String value) {
        if (value == null || value.isBlank()) {
            return NORMAL;
        }
        try {
            return PowerUpSpawnRule.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return NORMAL;
        }
    }
}
