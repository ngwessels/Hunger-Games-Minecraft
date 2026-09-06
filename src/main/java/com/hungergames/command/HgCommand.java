package com.hungergames.command;

import com.hungergames.HungerGamesPlugin;
import com.hungergames.arena.Arena;
import com.hungergames.arena.ArenaGenerator;
import com.hungergames.gamemode.GameMode;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class HgCommand implements CommandExecutor {

    private final HungerGamesPlugin plugin;

    public HgCommand(HungerGamesPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        return switch (args[0].toLowerCase()) {
            case "reload" -> handleReload(sender);
            case "arena" -> handleArena(sender, args);
            case "join" -> handleJoin(sender, args);
            case "rejoin" -> handleRejoin(sender);
            case "leave" -> handleLeave(sender);
            case "start" -> handleStart(sender, args);
            case "simulate" -> handleSimulate(sender, args);
            case "stop" -> handleStop(sender, args);
            case "loot" -> handleLoot(sender, args);
            case "powerup" -> handlePowerUp(sender, args);
            case "reset" -> handleReset(sender, args);
            case "unstuck" -> handleUnstuck(sender, args);
            default -> {
                sendHelp(sender);
                yield true;
            }
        };
    }

    private boolean handleReload(CommandSender sender) {
        if (!requireAdmin(sender)) {
            return true;
        }
        plugin.reload();
        sender.sendMessage(plugin.formatMessage("&aConfiguration reloaded."));
        plugin.getGameManager().getActiveGame().ifPresent(game ->
                sender.sendMessage(plugin.formatMessage("&7Cornucopia chests refreshed for active match.")));
        return true;
    }

    private boolean handleArena(CommandSender sender, String[] args) {
        if (!requireAdmin(sender)) {
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(plugin.formatMessage("&cUsage: /hg arena <create|setmode|generate|delete|list>"));
            return true;
        }

        return switch (args[1].toLowerCase()) {
            case "create" -> handleArenaCreate(sender, args);
            case "setmode" -> handleArenaSetMode(sender, args);
            case "generate" -> handleArenaGenerate(sender, args);
            case "delete" -> handleArenaDelete(sender, args);
            case "list" -> handleArenaList(sender);
            default -> {
                sender.sendMessage(plugin.formatMessage("&cUnknown arena subcommand."));
                yield true;
            }
        };
    }

    private boolean handleArenaCreate(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(plugin.formatMessage("&cUsage: /hg arena create <name>"));
            return true;
        }
        String name = args[2];
        plugin.getArenaManager().createArena(name);
        sender.sendMessage(plugin.formatMessage("&aArena &e" + name + " &acreated at &f0, "
                + plugin.getPluginConfig().getBaseTerrainY() + ", 0 &awith radius &f"
                + Arena.RADIUS + " &aand wall height &f" + Arena.WALL_HEIGHT + "&a."));
        sender.sendMessage(plugin.formatMessage("&7Next: &f/hg arena setmode " + name + " <gamemode> &7then &f/hg arena generate " + name));
        return true;
    }

    private boolean handleArenaSetMode(CommandSender sender, String[] args) {
        if (args.length < 4) {
            sender.sendMessage(plugin.formatMessage("&cUsage: /hg arena setmode <name> <gamemode>"));
            return true;
        }
        Arena arena = plugin.getArenaManager().getArena(args[2]).orElse(null);
        if (arena == null) {
            sender.sendMessage(plugin.formatMessage("&cArena not found."));
            return true;
        }
        if (plugin.getGameModeRegistry().getMode(args[3]).isEmpty()) {
            sender.sendMessage(plugin.formatMessage("&cGame mode not found."));
            return true;
        }
        arena.setGameModeId(args[3]);
        GameMode mode = plugin.getGameModeRegistry().getMode(args[3]).orElse(null);
        if (mode != null) {
            arena.setRadius(mode.getArenaRadius());
        }
        plugin.getArenaManager().save();
        sender.sendMessage(plugin.formatMessage("&aArena &e" + arena.getName() + " &amode set to &f" + args[3]
                + " &a(radius &f" + arena.getRadius() + "&a)."));
        return true;
    }

    private boolean handleArenaGenerate(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.formatMessage("&cPlayers only."));
            return true;
        }
        if (args.length < 3) {
            sender.sendMessage(plugin.formatMessage("&cUsage: /hg arena generate <name>"));
            return true;
        }
        Arena arena = plugin.getArenaManager().getArena(args[2]).orElse(null);
        if (arena == null) {
            sender.sendMessage(plugin.formatMessage("&cArena not found."));
            return true;
        }
        if (arena.getRadius() <= 0) {
            sender.sendMessage(plugin.formatMessage("&cArena has no radius configured."));
            return true;
        }
        GameMode mode = plugin.getGameModeRegistry().getModeOrDefault(arena.getGameModeId());
        new ArenaGenerator(plugin).generate(arena, mode, player, null);
        return true;
    }

    private boolean handleArenaDelete(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(plugin.formatMessage("&cUsage: /hg arena delete <name>"));
            return true;
        }
        if (plugin.getArenaManager().deleteArena(args[2])) {
            sender.sendMessage(plugin.formatMessage("&aArena deleted."));
        } else {
            sender.sendMessage(plugin.formatMessage("&cArena not found."));
        }
        return true;
    }

    private boolean handleArenaList(CommandSender sender) {
        if (plugin.getArenaManager().getArenas().isEmpty()) {
            sender.sendMessage(plugin.formatMessage("&7No arenas configured."));
            return true;
        }
        sender.sendMessage(plugin.formatMessage("&6Arenas:"));
        for (Arena arena : plugin.getArenaManager().getArenas()) {
            String status = arena.isGenerated() ? "&agenerated" : "&cnot generated";
            sender.sendMessage(plugin.formatMessage("&e" + arena.getName() + " &7- radius &f" + arena.getRadius()
                    + " &7- mode &f" + arena.getGameModeId() + " &7- " + status));
        }
        return true;
    }

    private boolean handleJoin(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.formatMessage("&cPlayers only."));
            return true;
        }
        if (!player.hasPermission("hungergames.play")) {
            sender.sendMessage(plugin.formatMessage("&cNo permission."));
            return true;
        }
        if (plugin.getGameManager().hasRejoinableSession(player)) {
            sender.sendMessage(plugin.formatMessage(
                    "&eYou already have a match in progress. Use &f/hg rejoin &eto return."));
            return true;
        }

        Arena arena;
        if (args.length >= 2) {
            arena = plugin.getArenaManager().getArena(args[1]).orElse(null);
        } else {
            arena = plugin.getArenaManager().getArenas().stream()
                    .filter(Arena::isGenerated)
                    .findFirst()
                    .orElse(null);
        }

        if (arena == null || !arena.isGenerated()) {
            sender.sendMessage(plugin.formatMessage("&cNo available arena. Ask an admin to create one."));
            return true;
        }

        var game = plugin.getGameManager().joinGame(player, arena);
        if (game == null) {
            sender.sendMessage(plugin.formatMessage("&cCould not join — game full or already started."));
            return true;
        }
        sender.sendMessage(plugin.formatMessage("&aYou joined &e" + arena.getName() + "&a!"));
        return true;
    }

    private boolean handleRejoin(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.formatMessage("&cPlayers only."));
            return true;
        }
        if (!player.hasPermission("hungergames.play")) {
            sender.sendMessage(plugin.formatMessage("&cNo permission."));
            return true;
        }
        switch (plugin.getGameManager().rejoinGame(player)) {
            case SUCCESS -> sender.sendMessage(plugin.formatMessage("&aYou rejoined the match!"));
            case SUCCESS_SPECTATOR -> sender.sendMessage(plugin.formatMessage(
                    "&7You rejoined as a spectator."));
            case ALREADY_IN -> sender.sendMessage(plugin.formatMessage("&cYou're already in the match."));
            case MATCH_ENDED -> sender.sendMessage(plugin.formatMessage("&cThat match has ended."));
            case NOT_ELIGIBLE -> sender.sendMessage(plugin.formatMessage(
                    "&cNo match to rejoin. Use &f/hg join &cif you want to play."));
        }
        return true;
    }

    private boolean handleLeave(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.formatMessage("&cPlayers only."));
            return true;
        }
        plugin.getGameManager().leaveGame(player);
        sender.sendMessage(plugin.formatMessage("&7You left the game."));
        return true;
    }

    private boolean handleStart(CommandSender sender, String[] args) {
        if (!requireAdmin(sender)) {
            return true;
        }
        String arenaName;
        if (args.length >= 2) {
            arenaName = args[1];
        } else {
            Arena first = plugin.getArenaManager().getArenas().stream().findFirst().orElse(null);
            if (first == null) {
                sender.sendMessage(plugin.formatMessage("&cNo arenas available."));
                return true;
            }
            arenaName = first.getName();
        }
        var gameOpt = plugin.getGameManager().getGame(arenaName);
        if (gameOpt.isPresent() && gameOpt.get().getState() != com.hungergames.game.GameState.LOBBY) {
            sender.sendMessage(plugin.formatMessage("&cGame already running."));
            return true;
        }
        int joined = gameOpt.map(g -> g.getSessions().size()).orElse(0);
        if (joined < 1) {
            sender.sendMessage(plugin.formatMessage("&cNo players in lobby — run &f/hg join " + arenaName + " &cfirst."));
            return true;
        }
        if (plugin.getGameManager().startGame(arenaName)) {
            sender.sendMessage(plugin.formatMessage("&aGame started!"));
        } else {
            sender.sendMessage(plugin.formatMessage("&cCould not start the game. Check arena is generated and try again."));
        }
        return true;
    }

    private boolean handleSimulate(CommandSender sender, String[] args) {
        if (!requireAdmin(sender)) {
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.formatMessage("&cPlayers only."));
            return true;
        }
        String arenaName;
        if (args.length >= 2) {
            arenaName = args[1];
        } else {
            Arena first = plugin.getArenaManager().getArenas().stream().findFirst().orElse(null);
            if (first == null) {
                sender.sendMessage(plugin.formatMessage("&cNo arenas available."));
                return true;
            }
            arenaName = first.getName();
        }
        var gameOpt = plugin.getGameManager().getGame(arenaName);
        if (gameOpt.isPresent() && gameOpt.get().getState() != com.hungergames.game.GameState.LOBBY) {
            sender.sendMessage(plugin.formatMessage("&cGame already running."));
            return true;
        }
        int joined = gameOpt.map(g -> g.getSessions().size()).orElse(0);
        if (joined > 0) {
            sender.sendMessage(plugin.formatMessage("&cLobby has players — use &f/hg start " + arenaName
                    + " &cor wait for them to leave."));
            return true;
        }
        if (plugin.getGameManager().simulateGame(arenaName, player)) {
            sender.sendMessage(plugin.formatMessage("&aSimulation started for arena &e" + arenaName + "&a!"));
        } else {
            sender.sendMessage(plugin.formatMessage("&cCould not start simulation. Check arena is generated and try again."));
        }
        return true;
    }

    private boolean handleStop(CommandSender sender, String[] args) {
        if (!requireAdmin(sender)) {
            return true;
        }
        String arenaName = args.length >= 2 ? args[1] : null;
        if (arenaName == null) {
            plugin.getGameManager().getActiveGame().ifPresentOrElse(
                    game -> plugin.getGameManager().forceStop(game.getArena().getName()),
                    () -> sender.sendMessage(plugin.formatMessage("&cNo active game."))
            );
        } else {
            plugin.getGameManager().forceStop(arenaName);
            sender.sendMessage(plugin.formatMessage("&aGame stopped."));
        }
        return true;
    }

    private boolean handleUnstuck(CommandSender sender, String[] args) {
        if (args.length < 2) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(plugin.formatMessage("&cUsage: /hg unstuck <player|*>"));
                return true;
            }
            applyUnstuck(sender, player);
            return true;
        }
        if (!requireAdmin(sender)) {
            return true;
        }
        if (args[1].equals("*") || args[1].equalsIgnoreCase("all")) {
            int cleared = 0;
            int skipped = 0;
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (plugin.getGameManager().clearLeftoverMatchFlags(player)) {
                    player.sendMessage(plugin.formatMessage("&aMatch invulnerability cleared."));
                    cleared++;
                } else {
                    skipped++;
                }
            }
            sender.sendMessage(plugin.formatMessage("&aCleared leftover invulnerability on &f" + cleared
                    + " &aplayer(s)." + (skipped > 0 ? " &7Skipped " + skipped + " still in a match." : "")));
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null || !target.isOnline()) {
            sender.sendMessage(plugin.formatMessage("&cPlayer not found."));
            return true;
        }
        applyUnstuck(sender, target);
        return true;
    }

    private void applyUnstuck(CommandSender sender, Player target) {
        if (!plugin.getGameManager().clearLeftoverMatchFlags(target)) {
            sender.sendMessage(plugin.formatMessage("&c" + target.getName()
                    + " is in a live match. Wait until it ends."));
            return;
        }
        if (sender.equals(target)) {
            sender.sendMessage(plugin.formatMessage("&aMatch invulnerability cleared."));
        } else {
            sender.sendMessage(plugin.formatMessage("&aCleared leftover invulnerability on &f"
                    + target.getName() + "&a."));
            target.sendMessage(plugin.formatMessage("&aMatch invulnerability cleared."));
        }
    }

    private boolean handleReset(CommandSender sender, String[] args) {
        if (!requireAdmin(sender)) {
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(plugin.formatMessage("&cUsage: /hg reset <arena>"));
            return true;
        }
        Arena arena = plugin.getArenaManager().getArena(args[1]).orElse(null);
        if (arena == null) {
            sender.sendMessage(plugin.formatMessage("&cArena not found."));
            return true;
        }
        if (!arena.isGenerated()) {
            sender.sendMessage(plugin.formatMessage("&cArena has not been generated yet. Use &f/hg arena generate "
                    + arena.getName() + " &cfirst."));
            return true;
        }
        plugin.getGameManager().prepareArenaForReset(arena);
        GameMode mode = plugin.getGameModeRegistry().getModeOrDefault(arena.getGameModeId());
        Player player = sender instanceof Player p ? p : null;
        if (player == null) {
            sender.sendMessage(plugin.formatMessage("&7Restoring arena &e" + arena.getName()
                    + "&7 from saved copy..."));
        }
        new ArenaGenerator(plugin).reset(arena, mode, player, () -> {
            if (!(sender instanceof Player)) {
                sender.sendMessage(plugin.formatMessage("&aArena &e" + arena.getName() + " &areset!"));
            }
        });
        return true;
    }

    private boolean handleLoot(CommandSender sender, String[] args) {
        if (!requireAdmin(sender)) {
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(plugin.formatMessage("&cUsage: /hg loot <give|spawnbox|refill|list>"));
            return true;
        }
        return switch (args[1].toLowerCase()) {
            case "give" -> handleLootGive(sender, args);
            case "spawnbox" -> handleLootSpawnBox(sender, args);
            case "refill" -> handleLootRefill(sender, args);
            case "list" -> handleLootList(sender);
            default -> {
                sender.sendMessage(plugin.formatMessage("&cUnknown loot subcommand."));
                yield true;
            }
        };
    }

    private boolean handleLootGive(CommandSender sender, String[] args) {
        if (args.length < 4) {
            sender.sendMessage(plugin.formatMessage("&cUsage: /hg loot give <player> <itemId>"));
            return true;
        }
        Player target = plugin.getServer().getPlayer(args[2]);
        if (target == null) {
            sender.sendMessage(plugin.formatMessage("&cPlayer not found."));
            return true;
        }
        var item = plugin.getCustomItemRegistry().createItem(args[3]);
        if (item.isEmpty()) {
            sender.sendMessage(plugin.formatMessage("&cCustom item not found."));
            return true;
        }
        target.getInventory().addItem(item.get());
        sender.sendMessage(plugin.formatMessage("&aGave &f" + args[3] + " &ato &e" + target.getName() + "&a."));
        return true;
    }

    private boolean handleLootSpawnBox(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.formatMessage("&cPlayers only."));
            return true;
        }
        String tier = args.length >= 3 ? args[2] : "normal";
        plugin.getGameManager().getActiveGame().ifPresentOrElse(game -> {
            plugin.getLootBoxManager().placeLootBox(game, player.getLocation(), tier);
            sender.sendMessage(plugin.formatMessage("&aLoot chest placed."));
        }, () -> {
            plugin.getGameManager().getPlayerGame(player).ifPresentOrElse(game -> {
                plugin.getLootBoxManager().placeLootBox(game, player.getLocation(), tier);
                sender.sendMessage(plugin.formatMessage("&aLoot chest placed."));
            }, () -> sender.sendMessage(plugin.formatMessage("&cNo active game to attach loot chest to.")));
        });
        return true;
    }

    private boolean handleLootRefill(CommandSender sender, String[] args) {
        if (args.length >= 3) {
            int filled = plugin.getLootBoxManager().refillCornucopiaForArena(args[2]);
            if (filled < 0) {
                sender.sendMessage(plugin.formatMessage("&cArena not found."));
                return true;
            }
            sender.sendMessage(plugin.formatMessage("&aFilled &f" + filled + " &acornucopia chest(s) in &e" + args[2] + "&a."));
            return true;
        }

        boolean refilled = plugin.getGameManager().getActiveGame().map(game -> {
            int filled = plugin.getLootBoxManager().fillCornucopia(game);
            sender.sendMessage(plugin.formatMessage("&aFilled &f" + filled + " &acornucopia chest(s) for the active match."));
            return true;
        }).orElse(false);

        if (!refilled && sender instanceof Player player) {
            refilled = plugin.getGameManager().getPlayerGame(player).map(game -> {
                int filled = plugin.getLootBoxManager().fillCornucopia(game);
                sender.sendMessage(plugin.formatMessage("&aFilled &f" + filled + " &acornucopia chest(s) for your match."));
                return true;
            }).orElse(false);
        }

        if (!refilled) {
            sender.sendMessage(plugin.formatMessage("&cNo active game. Use &e/hg loot refill <arena>&c."));
        }
        return true;
    }

    private boolean handleLootList(CommandSender sender) {
        sender.sendMessage(plugin.formatMessage("&6Custom items:"));
        plugin.getCustomItemRegistry().getDefinitions().forEach(def ->
                sender.sendMessage(plugin.formatMessage("&e" + def.getId() + " &7- " + def.getDisplayName())));
        return true;
    }

    private boolean handlePowerUp(CommandSender sender, String[] args) {
        if (!requireAdmin(sender)) {
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(plugin.formatMessage("&cUsage: /hg powerup <list|spawn> [id]"));
            return true;
        }
        return switch (args[1].toLowerCase()) {
            case "list" -> handlePowerUpList(sender);
            case "spawn" -> handlePowerUpSpawn(sender, args);
            default -> {
                sender.sendMessage(plugin.formatMessage("&cUnknown powerup subcommand."));
                yield true;
            }
        };
    }

    private boolean handlePowerUpList(CommandSender sender) {
        sender.sendMessage(plugin.formatMessage("&6Power-ups:"));
        plugin.getPowerUpRegistry().getDefinitions().forEach(def ->
                sender.sendMessage(plugin.formatMessage("&e" + def.getId() + " &7- " + def.getDisplayName())));
        return true;
    }

    private boolean handlePowerUpSpawn(CommandSender sender, String[] args) {
        var gameOpt = plugin.getGameManager().getActiveGame();
        if (gameOpt.isEmpty()) {
            sender.sendMessage(plugin.formatMessage("&cNo active game."));
            return true;
        }
        String id = args.length >= 3 ? args[2] : null;
        boolean spawned = id != null
                ? plugin.getPowerUpManager().forceSpawn(gameOpt.get(), id)
                : plugin.getPowerUpRegistry().rollNormal()
                        .map(def -> plugin.getPowerUpManager().forceSpawn(gameOpt.get(), def.getId()))
                        .orElse(false);
        if (spawned) {
            sender.sendMessage(plugin.formatMessage("&aPower-up spawned."));
        } else {
            sender.sendMessage(plugin.formatMessage("&cCould not spawn power-up."));
        }
        return true;
    }

    private boolean requireAdmin(CommandSender sender) {
        if (!sender.hasPermission("hungergames.admin")) {
            sender.sendMessage(plugin.formatMessage("&cNo permission."));
            return false;
        }
        return true;
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(plugin.formatMessage("&6&lHunger Games Commands"));
        sender.sendMessage(plugin.formatMessage("&e/hg join [arena] &7- Join a game"));
        sender.sendMessage(plugin.formatMessage("&e/hg rejoin &7- Rejoin after a disconnect or kick"));
        sender.sendMessage(plugin.formatMessage("&e/hg leave &7- Leave current game"));
        sender.sendMessage(plugin.formatMessage("&e/hg unstuck &7- Clear leftover match invulnerability"));
        if (sender.hasPermission("hungergames.admin")) {
            sender.sendMessage(plugin.formatMessage("&e/hg arena create <name> &7- Create arena at 0,20,0 (radius "
                    + Arena.RADIUS + ", wall " + Arena.WALL_HEIGHT + ")"));
            sender.sendMessage(plugin.formatMessage("&e/hg arena setmode <name> <mode> &7- Set game mode"));
            sender.sendMessage(plugin.formatMessage("&e/hg arena generate <name> &7- Build arena"));
            sender.sendMessage(plugin.formatMessage("&e/hg reset <arena> &7- Restore arena to post-generation state"));
            sender.sendMessage(plugin.formatMessage("&e/hg start [arena] &7- Start game"));
            sender.sendMessage(plugin.formatMessage("&e/hg simulate [arena] &7- Run match with no players; grants all custom items at grace"));
            sender.sendMessage(plugin.formatMessage("&e/hg loot give <player> <item> &7- Give custom item"));
            sender.sendMessage(plugin.formatMessage("&e/hg powerup list|spawn [id] &7- Power-up admin"));
            sender.sendMessage(plugin.formatMessage("&e/hg reload &7- Reload configs"));
            sender.sendMessage(plugin.formatMessage("&e/hg unstuck [player|*] &7- Clear leftover invulnerability"));
        }
    }
}
