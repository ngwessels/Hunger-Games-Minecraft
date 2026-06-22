package com.hungergames.util;

import org.bukkit.ChatColor;

import java.util.ArrayList;
import java.util.List;

public final class ColorUtil {

    private ColorUtil() {
    }

    public static String color(String input) {
        if (input == null) {
            return "";
        }
        return ChatColor.translateAlternateColorCodes('&', input);
    }

    public static List<String> color(List<String> lines) {
        List<String> colored = new ArrayList<>();
        for (String line : lines) {
            colored.add(color(line));
        }
        return colored;
    }

    public static String stripColor(String input) {
        if (input == null) {
            return "";
        }
        return ChatColor.stripColor(input);
    }
}
