package com.hungergames.game;

import org.bukkit.GameRule;
import org.bukkit.World;

import java.util.HashMap;
import java.util.Map;

/**
 * Hides the vanilla locator bar (the compass-style player tracker shown above the
 * hotbar, added in MC 1.21.6) inside arena worlds while a match is running. There is
 * no per-player API for this feature, so it is toggled through the per-world
 * "locatorBar" game rule; each arena lives in its own world, which keeps the change
 * scoped to arena participants. The previous rule value is captured so the world is
 * returned to its original state once the match ends.
 */
public class LocatorBarManager {

    private static final String GAME_RULE_NAME = "locatorBar";

    private final Map<String, Boolean> savedValuesByWorld = new HashMap<>();

    public void suppress(World world) {
        if (world == null || savedValuesByWorld.containsKey(world.getName())) {
            return;
        }
        Boolean current = getValue(world);
        if (current == null || !current) {
            return;
        }
        savedValuesByWorld.put(world.getName(), true);
        setValue(world, false);
    }

    public void restore(World world) {
        if (world == null) {
            return;
        }
        Boolean saved = savedValuesByWorld.remove(world.getName());
        if (saved == null) {
            return;
        }
        if (getValue(world) != null) {
            setValue(world, saved);
        }
    }

    private Boolean getValue(World world) {
        GameRule<Boolean> rule = castBooleanRule(GameRule.getByName(GAME_RULE_NAME));
        return rule == null ? null : world.getGameRuleValue(rule);
    }

    private void setValue(World world, boolean value) {
        GameRule<Boolean> rule = castBooleanRule(GameRule.getByName(GAME_RULE_NAME));
        if (rule != null) {
            world.setGameRule(rule, value);
        }
    }

    @SuppressWarnings("unchecked")
    private static GameRule<Boolean> castBooleanRule(GameRule<?> rule) {
        if (rule == null || !Boolean.class.equals(rule.getType())) {
            return null;
        }
        return (GameRule<Boolean>) rule;
    }
}
