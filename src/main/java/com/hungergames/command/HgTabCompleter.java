package com.hungergames.command;

import com.hungergames.HungerGamesPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class HgTabCompleter implements TabCompleter {

    private final HungerGamesPlugin plugin;

    public HgTabCompleter(HungerGamesPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length >= 1 && !sender.hasPermission("hungergames.admin")
                && !args[0].equalsIgnoreCase("join") && !args[0].equalsIgnoreCase("leave")
                && !args[0].equalsIgnoreCase("rejoin") && !args[0].equalsIgnoreCase("unstuck")) {
            return List.of();
        }

        if (args.length == 1) {
            return filter(List.of("arena", "join", "rejoin", "leave", "unstuck", "start", "simulate", "stop", "reset", "reload", "loot", "powerup"), args[0]);
        }

        if (args[0].equalsIgnoreCase("arena")) {
            if (args.length == 2) {
                return filter(List.of("create", "setmode", "generate", "delete", "list"), args[1]);
            }
            if (args.length == 3 && List.of("setmode", "generate", "delete").contains(args[1].toLowerCase())) {
                return filterArenaNames(args[2]);
            }
            if (args.length == 4 && args[1].equalsIgnoreCase("setmode")) {
                return filter(plugin.getGameModeRegistry().getModes().stream()
                        .map(m -> m.getId()).collect(Collectors.toList()), args[3]);
            }
        }

        if (args[0].equalsIgnoreCase("join") && args.length == 2) {
            return filterArenaNames(args[1]);
        }

        if (args[0].equalsIgnoreCase("start") && args.length == 2) {
            return filterArenaNames(args[1]);
        }

        if (args[0].equalsIgnoreCase("simulate") && args.length == 2) {
            return filterArenaNames(args[1]);
        }

        if (args[0].equalsIgnoreCase("reset") && args.length == 2) {
            return filterArenaNames(args[1]);
        }

        if (args[0].equalsIgnoreCase("loot")) {
            if (args.length == 2) {
                return filter(List.of("give", "spawnbox", "refill", "list"), args[1]);
            }
            if (args.length == 3 && args[1].equalsIgnoreCase("give")) {
                return filter(plugin.getServer().getOnlinePlayers().stream()
                        .map(Player::getName).collect(Collectors.toList()), args[2]);
            }
            if (args.length == 4 && args[1].equalsIgnoreCase("give")) {
                return filter(plugin.getCustomItemRegistry().getDefinitions().stream()
                        .map(d -> d.getId()).collect(Collectors.toList()), args[3]);
            }
            if (args.length == 3 && args[1].equalsIgnoreCase("spawnbox")) {
                return filter(List.of("easy", "normal", "hard", "extreme"), args[2]);
            }
            if (args.length == 3 && args[1].equalsIgnoreCase("refill")) {
                return filterArenaNames(args[2]);
            }
        }

        if (args[0].equalsIgnoreCase("powerup")) {
            if (args.length == 2) {
                return filter(List.of("list", "spawn"), args[1]);
            }
            if (args.length == 3 && args[1].equalsIgnoreCase("spawn")) {
                return filter(plugin.getPowerUpRegistry().getDefinitions().stream()
                        .map(d -> d.getId()).collect(Collectors.toList()), args[2]);
            }
        }

        if (args[0].equalsIgnoreCase("unstuck") && args.length == 2) {
            List<String> names = plugin.getServer().getOnlinePlayers().stream()
                    .map(Player::getName).collect(Collectors.toList());
            names.add(0, "*");
            return filter(names, args[1]);
        }

        return List.of();
    }

    private List<String> filterArenaNames(String prefix) {
        return filter(plugin.getArenaManager().getArenas().stream()
                .map(a -> a.getName()).collect(Collectors.toList()), prefix);
    }

    private List<String> filter(List<String> options, String prefix) {
        String lower = prefix.toLowerCase();
        List<String> result = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase().startsWith(lower)) {
                result.add(option);
            }
        }
        return result;
    }
}
