package com.hungergames.game;

import com.hungergames.HungerGamesPlugin;
import com.hungergames.arena.Arena;
import com.hungergames.arena.ArenaGenerator;
import com.hungergames.util.ArenaEntityUtil;
import com.hungergames.util.BlockSnapshot;
import com.hungergames.util.SafeRespawnPlatformBuilder;
import com.hungergames.util.SafeRespawnPlacement;
import com.hungergames.util.SpawnAssignmentUtil;
import com.hungergames.util.SpawnLocationUtil;
import com.hungergames.gamemode.GameMode;
import org.bukkit.Bukkit;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class GameManager {

    private final HungerGamesPlugin plugin;
    private final Map<String, Game> gamesByArena = new HashMap<>();
    private final Map<UUID, String> playerArena = new HashMap<>();
    private final Map<UUID, SavedPlayerState> pendingRestores = new HashMap<>();
    private final LocatorBarManager locatorBarManager = new LocatorBarManager();
    private BukkitTask tickTask;

    public GameManager(HungerGamesPlugin plugin) {
        this.plugin = plugin;
        startTickTask();
    }

    private void startTickTask() {
        tickTask = new BukkitRunnable() {
            @Override
            public void run() {
                for (Game game : gamesByArena.values()) {
                    tickGame(game);
                }
            }
        }.runTaskTimer(plugin, 20L, 20L);
    }

    public void shutdown() {
        if (tickTask != null) {
            tickTask.cancel();
        }
        for (Game game : List.copyOf(gamesByArena.values())) {
            forceStop(game.getArena().getName());
        }
    }

    public Optional<Game> getActiveGame() {
        return gamesByArena.values().stream()
                .filter(g -> g.getState() != GameState.IDLE && g.getState() != GameState.RESET)
                .findFirst();
    }

    public Collection<Game> getRunningGames() {
        return gamesByArena.values();
    }

    public Optional<Game> getGame(String arenaName) {
        return Optional.ofNullable(gamesByArena.get(arenaName.toLowerCase()));
    }

    public Optional<Game> getPlayerGame(Player player) {
        Optional<Game> game = findGameForPlayer(player.getUniqueId());
        if (game.isEmpty()) {
            return game;
        }
        PlayerSession session = game.get().getSession(player.getUniqueId());
        if (session != null && session.isDisconnected()) {
            return Optional.empty();
        }
        return game;
    }

    public enum RejoinResult {
        SUCCESS,
        SUCCESS_SPECTATOR,
        NOT_ELIGIBLE,
        ALREADY_IN,
        MATCH_ENDED
    }

    /**
     * Strips leftover comet invulnerability and gravity modifiers from a player
     * who is not in a live match. Returns false if they are still in a match.
     */
    public boolean clearLeftoverMatchFlags(Player player) {
        Optional<Game> game = getPlayerGame(player);
        if (game.isPresent()) {
            GameState state = game.get().getState();
            if (state == GameState.COUNTDOWN || state == GameState.GRACE
                    || state == GameState.ACTIVE || state == GameState.ENDING) {
                return false;
            }
        }
        MatchKit.clearPostMatchFlags(player);
        plugin.getVolcanoComet().clearPlayerGravity(player);
        return true;
    }

    private Optional<Game> findGameForPlayer(UUID uuid) {
        String arena = playerArena.get(uuid);
        if (arena != null) {
            Game mapped = gamesByArena.get(arena);
            if (mapped != null) {
                return Optional.of(mapped);
            }
            playerArena.remove(uuid);
        }
        for (Game game : gamesByArena.values()) {
            if (game.isParticipant(uuid)) {
                playerArena.put(uuid, game.getArena().getName().toLowerCase());
                return Optional.of(game);
            }
        }
        return Optional.empty();
    }

    public Game joinGame(Player player, Arena arena) {
        pendingRestores.remove(player.getUniqueId());

        String arenaKey = arena.getName().toLowerCase();
        Game existing = gamesByArena.get(arenaKey);
        if (existing != null && existing.isParticipant(player.getUniqueId()) && !existing.allowsJoin()) {
            PlayerSession staleSession = existing.getSession(player.getUniqueId());
            if (staleSession != null && staleSession.isDisconnected()) {
                return null;
            }
            if (staleSession != null) {
                releasePlayerFromArena(existing, staleSession);
            }
            existing.removePlayer(player.getUniqueId());
            playerArena.remove(player.getUniqueId());
        }

        Game game = gamesByArena.computeIfAbsent(arenaKey, name -> {
            GameMode mode = plugin.getGameModeRegistry().getModeOrDefault(arena.getGameModeId());
            return new Game(arena, mode);
        });

        if (!game.allowsJoin()) {
            return null;
        }
        if (game.getSessions().size() >= game.getGameMode().getMaxPlayers()) {
            return null;
        }
        if (game.isParticipant(player.getUniqueId())) {
            playerArena.put(player.getUniqueId(), arenaKey);
            return game;
        }

        game.addPlayer(player);
        playerArena.put(player.getUniqueId(), arenaKey);
        broadcast(game, "&a" + player.getName() + " &7joined the game! (&f" + game.getSessions().size()
                + "&7/" + game.getGameMode().getMaxPlayers() + "&7)");
        return game;
    }

    public void leaveGame(Player player) {
        findGameForPlayer(player.getUniqueId()).ifPresent(game -> {
            PlayerSession session = game.getSession(player.getUniqueId());
            if (session != null) {
                releasePlayerFromArena(game, session);
            }
            game.removePlayer(player.getUniqueId());
            playerArena.remove(player.getUniqueId());
            if (game.getSessions().isEmpty()) {
                if (game.getState() != GameState.LOBBY && game.getState() != GameState.RESET) {
                    if (!game.isSimulationMode()) {
                        abandonGame(game);
                    }
                } else {
                    gamesByArena.remove(game.getArena().getName().toLowerCase());
                }
            }
        });
    }

    public void applyPendingRestore(Player player) {
        SavedPlayerState pending = pendingRestores.remove(player.getUniqueId());
        if (pending == null) {
            return;
        }
        plugin.getGameHudManager().clearHudForPlayer(player);
        plugin.getCustomItemRegistry().stripFromPlayers(List.of(player));
        PlayerSession.applySavedState(player, pending);
    }

    public boolean hasRejoinableSession(Player player) {
        return findRejoinSession(player).isPresent();
    }

    public void notifyRejoinOpportunity(Player player) {
        Optional<Game> gameOpt = findGameForPlayer(player.getUniqueId());
        if (gameOpt.isEmpty()) {
            return;
        }
        Game game = gameOpt.get();
        PlayerSession session = game.getSession(player.getUniqueId());
        if (session == null || !session.isDisconnected()) {
            return;
        }
        GameState state = game.getState();
        if (state != GameState.COUNTDOWN && state != GameState.GRACE && state != GameState.ACTIVE) {
            return;
        }
        expireDisconnectedIfNeeded(game, session);
        session.rebindPlayer(player);
        if (session.isAlive() && !session.isSpectator()) {
            int remaining = getRejoinSecondsRemaining(session);
            String window = remaining < 0
                    ? "the match ends"
                    : remaining + "s";
            player.sendMessage(plugin.formatMessage(
                    "&eYou disconnected from a match. Use &f/hg rejoin &ewithin &f"
                            + window + " &eto return."));
        } else {
            player.sendMessage(plugin.formatMessage(
                    "&7You can still watch — use &f/hg rejoin &7to spectate."));
        }
    }

    public RejoinResult rejoinGame(Player player) {
        Optional<Game> mapped = findGameForPlayer(player.getUniqueId());
        if (mapped.isEmpty()) {
            return RejoinResult.NOT_ELIGIBLE;
        }
        Game game = mapped.get();
        PlayerSession session = game.getSession(player.getUniqueId());
        if (session == null) {
            return RejoinResult.NOT_ELIGIBLE;
        }
        if (!session.isDisconnected()) {
            return RejoinResult.ALREADY_IN;
        }
        GameState state = game.getState();
        if (state != GameState.COUNTDOWN && state != GameState.GRACE && state != GameState.ACTIVE) {
            return RejoinResult.MATCH_ENDED;
        }

        expireDisconnectedIfNeeded(game, session);
        session.rebindPlayer(player);
        playerArena.put(player.getUniqueId(), game.getArena().getName().toLowerCase());
        session.clearDisconnected();
        session.savePlayerState();

        boolean asSpectator = !session.isAlive() || session.isSpectator();
        if (asSpectator) {
            session.setAlive(false);
            session.setSpectator(true);
            respawnPlayerAsSpectator(game, player);
            plugin.getGameHudManager().update(game);
            broadcast(game, "&e" + player.getName() + " &7rejoined as a spectator.");
            return RejoinResult.SUCCESS_SPECTATOR;
        }

        restoreTributeOnRejoin(game, player, session);
        plugin.getGameHudManager().update(game);
        broadcast(game, "&a" + player.getName() + " &7rejoined the match!");
        return RejoinResult.SUCCESS;
    }

    public void handlePlayerQuit(Player player) {
        findGameForPlayer(player.getUniqueId()).ifPresent(game -> {
            PlayerSession session = game.getSession(player.getUniqueId());
            if (session != null && session.isDisconnected()) {
                return;
            }
            GameState state = game.getState();
            if (state == GameState.COUNTDOWN || state == GameState.GRACE || state == GameState.ACTIVE) {
                handleDisconnect(game, player);
                return;
            }
            leaveGame(player);
        });
    }

    private void handleDisconnect(Game game, Player player) {
        PlayerSession session = game.getSession(player.getUniqueId());
        if (session == null || session.isDisconnected()) {
            return;
        }
        if (game.getState() == GameState.GRACE || game.getState() == GameState.ACTIVE) {
            session.captureMatchSnapshot();
        }
        if (session.hasSavedState()) {
            stashPendingRestore(session);
        }
        plugin.getCurseManager().releaseBeastForm(player);
        plugin.getGameHudManager().clearHudForPlayer(player, game);
        session.markDisconnected();
        int remaining = getRejoinSecondsRemaining(session);
        String window = remaining < 0 ? "until the match ends" : remaining + "s";
        broadcast(game, "&e" + player.getName() + " &7disconnected. They can &f/hg rejoin &7for &f"
                + window + "&7.");
    }

    private Optional<PlayerSession> findRejoinSession(Player player) {
        return findGameForPlayer(player.getUniqueId())
                .map(game -> game.getSession(player.getUniqueId()))
                .filter(session -> session != null && session.isDisconnected())
                .filter(session -> {
                    Game game = findGameForPlayer(player.getUniqueId()).orElse(null);
                    if (game == null) {
                        return false;
                    }
                    GameState state = game.getState();
                    return state == GameState.COUNTDOWN || state == GameState.GRACE || state == GameState.ACTIVE;
                });
    }

    private int getRejoinSecondsRemaining(PlayerSession session) {
        int timeout = plugin.getPluginConfig().getRejoinTimeoutSeconds();
        if (timeout <= 0) {
            return -1;
        }
        long elapsed = (System.currentTimeMillis() - session.getDisconnectedAtMillis()) / 1000L;
        return (int) Math.max(0, timeout - elapsed);
    }

    private void restoreTributeOnRejoin(Game game, Player player, PlayerSession session) {
        GameState state = game.getState();
        if (session.applyMatchSnapshot(player)) {
            plugin.getCustomItemListener().updatePassives(player);
            if (plugin.getCurseManager().isBeastCursed(session)) {
                plugin.getCurseManager().stripBeastInventory(player);
            }
            plugin.getCurseManager().reapplyOnRespawn(session);
            session.applyMatchVitals(player);
            player.setAllowFlight(false);
            player.setFlying(false);
            if (!plugin.getCurseManager().isBeastCursed(session)) {
                player.setInvisible(false);
            }
            MatchKit.clearPostMatchFlags(player);
            if (session.isInRespawnGrace()) {
                player.setGlowing(true);
            }
            return;
        }
        if (state == GameState.GRACE || state == GameState.ACTIVE) {
            equipPlayerForMatch(game, player);
            plugin.getCurseManager().reapplyOnRespawn(session);
            return;
        }
        player.getInventory().clear();
        player.getInventory().setArmorContents(new ItemStack[4]);
        player.getInventory().setItemInOffHand(null);
    }

    private void tickDisconnectedPlayers(Game game) {
        for (PlayerSession session : List.copyOf(game.getSessions().values())) {
            expireDisconnectedIfNeeded(game, session);
        }
    }

    private void expireDisconnectedIfNeeded(Game game, PlayerSession session) {
        if (!session.isDisconnected() || !session.isAlive() || session.isSpectator()) {
            return;
        }
        int remaining = getRejoinSecondsRemaining(session);
        if (remaining != 0) {
            return;
        }
        session.setAlive(false);
        session.setSpectator(true);
        session.clearMatchSnapshot();
        broadcast(game, "&c" + session.getPlayerName() + " &7failed to rejoin and is out of the match!");
        Player online = Bukkit.getPlayer(session.getPlayerId());
        if (online != null && online.isOnline()) {
            online.sendMessage(plugin.formatMessage(
                    "&cYour rejoin window expired. Use &f/hg rejoin &cto spectate."));
        }
        checkWinCondition(game);
    }

    private void releasePlayerFromArena(Game game, PlayerSession session) {
        UUID uuid = session.getPlayerId();
        Player online = Bukkit.getPlayer(uuid);

        plugin.getCurseManager().clearPlayer(session);
        restoreTempRespawnPlatform(game, session);
        clearRespawnGrace(game, online != null ? online : session.getPlayer(), session);

        if (session.isDisconnected()) {
            if (online != null && online.isOnline()) {
                MatchKit.clearPostMatchFlags(online);
                plugin.getVolcanoComet().clearPlayerGravity(online);
                plugin.getGameHudManager().clearHudForPlayer(online, game);
            } else if (session.hasSavedState()) {
                stashPendingRestore(session);
            }
            return;
        }

        if (online != null && online.isOnline()) {
            MatchKit.clearPostMatchFlags(online);
            plugin.getVolcanoComet().clearPlayerGravity(online);
            plugin.getGameHudManager().clearHudForPlayer(online, game);
            plugin.getCustomItemRegistry().stripFromPlayers(List.of(online));
            if (!session.restorePlayerState() && session.hasSavedState()) {
                stashPendingRestore(session);
            }
        } else if (session.hasSavedState()) {
            stashPendingRestore(session);
        }
    }

    private void stashPendingRestore(PlayerSession session) {
        SavedPlayerState snapshot = session.takeSavedState();
        if (snapshot != null) {
            pendingRestores.put(session.getPlayer().getUniqueId(), snapshot);
        }
    }

    public boolean startGame(String arenaName) {
        Game game = gamesByArena.get(arenaName.toLowerCase());
        if (game == null) {
            Arena arena = plugin.getArenaManager().getArena(arenaName).orElse(null);
            if (arena == null || !arena.isGenerated()) {
                return false;
            }
            GameMode mode = plugin.getGameModeRegistry().getModeOrDefault(arena.getGameModeId());
            game = new Game(arena, mode);
            gamesByArena.put(arenaName.toLowerCase(), game);
        }

        if (plugin.getArenaManager().getOrCreateWorld(game.getArena()) == null) {
            return false;
        }

        if (game.getState() != GameState.LOBBY) {
            return false;
        }
        if (game.getSessions().size() < getMinPlayersRequired(game)) {
            return false;
        }

        game.setState(GameState.COUNTDOWN);
        game.setCountdownRemaining(plugin.getPluginConfig().getDefaultCountdown());
        broadcast(game, "&eGame starting in &f" + game.getCountdownRemaining() + " &eseconds!");
        return true;
    }

    public boolean simulateGame(String arenaName, Player observer) {
        Game game = gamesByArena.get(arenaName.toLowerCase());
        if (game == null) {
            Arena arena = plugin.getArenaManager().getArena(arenaName).orElse(null);
            if (arena == null || !arena.isGenerated()) {
                return false;
            }
            GameMode mode = plugin.getGameModeRegistry().getModeOrDefault(arena.getGameModeId());
            game = new Game(arena, mode);
            gamesByArena.put(arenaName.toLowerCase(), game);
        }

        if (plugin.getArenaManager().getOrCreateWorld(game.getArena()) == null) {
            return false;
        }

        if (game.getState() != GameState.LOBBY) {
            return false;
        }
        if (!game.getSessions().isEmpty()) {
            return false;
        }

        game.setSimulationMode(true);
        game.setSimulationObserverId(observer.getUniqueId());
        game.setSimulationObserverState(PlayerSession.capturePlayerState(observer));
        teleportSimulationObserver(game, observer);

        game.setState(GameState.COUNTDOWN);
        game.setCountdownRemaining(plugin.getPluginConfig().getDefaultCountdown());
        broadcast(game, "&eSimulation starting in &f" + game.getCountdownRemaining() + " &eseconds!");
        observer.sendMessage(plugin.formatMessage("&7You are spectating this simulation. Use &f/hg stop "
                + game.getArena().getName() + " &7to end early."));
        observer.sendMessage(plugin.formatMessage("&7At grace you will receive all custom items to test."));
        return true;
    }

    private void teleportSimulationObserver(Game game, Player observer) {
        Location spawn = resolveSpectatorSpawn(game);
        if (spawn != null) {
            observer.teleport(spawn);
        }
        observer.setGameMode(org.bukkit.GameMode.SPECTATOR);
        observer.setAllowFlight(true);
        observer.setFlying(true);
        observer.setInvisible(false);
    }

    public Location resolveSpectatorSpawn(Game game) {
        Arena arena = game.getArena();
        Location spawn = arena.getCenterSpawn();
        if (spawn == null) {
            World world = arena.getWorld();
            if (world != null) {
                spawn = SpawnLocationUtil.findSafeSpawn(world, (int) arena.getCenterX(), (int) arena.getCenterZ(),
                        (int) arena.getCenterY());
            }
        }
        return spawn;
    }

    public void respawnPlayerAsSpectator(Game game, Player player) {
        PlayerSession session = game.getSession(player.getUniqueId());
        if (session == null) {
            return;
        }
        Player online = Bukkit.getPlayer(player.getUniqueId());
        if (online == null || !online.isOnline()) {
            return;
        }

        Location spawn = resolveSpectatorSpawn(game);
        if (spawn != null) {
            spawn.getWorld().getChunkAt(spawn);
        }
        if (online.isDead()) {
            online.spigot().respawn();
        }
        online.setGameMode(org.bukkit.GameMode.SPECTATOR);
        online.setAllowFlight(true);
        online.setFlying(true);
        online.setInvisible(true);
        online.setFireTicks(0);
        if (spawn != null) {
            online.teleport(spawn);
        }
    }

    private void restoreSimulationObserver(Game game) {
        if (!game.isSimulationMode()) {
            return;
        }
        UUID observerId = game.getSimulationObserverId();
        SavedPlayerState snapshot = game.getSimulationObserverState();
        SimulationTestTarget.despawnAll(game);
        if (observerId != null) {
            game.removePlayer(observerId);
            playerArena.remove(observerId);
        }
        game.setSimulationMode(false);
        game.setSimulationObserverId(null);
        game.setSimulationObserverState(null);
        if (observerId == null || snapshot == null) {
            return;
        }
        Player observer = Bukkit.getPlayer(observerId);
        if (observer != null && observer.isOnline()) {
            plugin.getCustomItemRegistry().stripFromPlayers(List.of(observer));
            PlayerSession.applySavedState(observer, snapshot);
        } else {
            pendingRestores.put(observerId, snapshot);
        }
    }

    public void forceStop(String arenaName) {
        getGame(arenaName).ifPresent(this::endAndReset);
    }

    public void prepareArenaForReset(Arena arena) {
        getGame(arena.getName()).ifPresent(game -> {
            plugin.getGameHudManager().clearHud(game);
            abandonGame(game, false);
        });
        World world = arena.getWorld();
        if (world != null) {
            ArenaEntityUtil.clearVolcanoEntities(world, arena);
            world.setStorm(false);
            world.setThundering(false);
            world.getWorldBorder().setSize(arena.getRadius() * 2.0);
            world.getWorldBorder().setDamageAmount(0);
        }
        plugin.getVolcanoComet().destroy();
        plugin.getBlockMutationQueue().clear();
    }

    private void tickGame(Game game) {
        if (game.getState() != GameState.LOBBY && game.getState() != GameState.RESET) {
            plugin.getGameHudManager().update(game);
        }
        switch (game.getState()) {
            case COUNTDOWN -> tickCountdown(game);
            case GRACE -> tickGrace(game);
            case ACTIVE -> tickActive(game);
            case ENDING -> tickEnding(game);
            case RESET -> tickReset(game);
            default -> {
            }
        }
    }

    private void tickCountdown(Game game) {
        tickDisconnectedPlayers(game);
        game.setCountdownRemaining(game.getCountdownRemaining() - 1);
        if (game.getCountdownRemaining() <= 0) {
            beginGrace(game);
        } else {
            broadcast(game, "&eStarting in &f" + game.getCountdownRemaining() + "&e...");
        }
    }

    private void beginGrace(Game game) {
        game.setState(GameState.GRACE);
        game.setMatchStartMillis(System.currentTimeMillis());
        game.setGraceRemaining(game.getGameMode().getGracePeriodSeconds());
        locatorBarManager.suppress(game.getArena().getWorld());
        if (game.isSimulationMode()) {
            enrollSimulationObserver(game);
        }
        World world = game.getArena().getWorld();
        ArenaEntityUtil.suppressVanillaMonsterSpawning(world);
        ArenaEntityUtil.clearPeacefulStartHostiles(world, game.getArena());
        teleportToSpawns(game);
        plugin.getLootBoxManager().prepareMatchLoot(game);
        plugin.getSettlementManager().onGraceStart(game);
        plugin.getArcticManager().onGraceStart(game);
        plugin.getTerrainPhaseManager().onMatchBegin(game);
        plugin.getVolcanoComet().start(game);
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (game.getState() != GameState.GRACE && game.getState() != GameState.ACTIVE) {
                return;
            }
            ArenaEntityUtil.clearPeacefulStartHostiles(game.getArena().getWorld(), game.getArena());
            if (game.isSimulationMode()) {
                spawnSimulationTestTarget(game);
            }
        });
        String lootMessage = plugin.getLootBoxManager().usesCornucopiaChests(game.getGameMode())
                ? "&aGrace period! Loot the center and scattered chests — PvP is &cdisabled&a."
                : "&aGrace period! Find scattered loot chests around the arena — PvP is &cdisabled&a.";
        broadcast(game, lootMessage);
    }

    private void enrollSimulationObserver(Game game) {
        UUID observerId = game.getSimulationObserverId();
        if (observerId == null || game.isParticipant(observerId)) {
            return;
        }
        Player observer = Bukkit.getPlayer(observerId);
        if (observer == null || !observer.isOnline()) {
            return;
        }
        PlayerSession session = new PlayerSession(observer);
        game.getSessions().put(observerId, session);
        playerArena.put(observerId, game.getArena().getName().toLowerCase());
    }

    private void spawnSimulationTestTarget(Game game) {
        UUID observerId = game.getSimulationObserverId();
        if (observerId == null) {
            return;
        }
        PlayerSession session = game.getSession(observerId);
        if (session == null) {
            return;
        }
        Location spawn = getSpawnLocation(game, session);
        SimulationTestTarget.spawn(game, spawn);
    }

    private void teleportToSpawns(Game game) {
        SpawnAssignmentUtil.assignMatchSpawns(game);
        for (PlayerSession session : game.getSessions().values()) {
            if (session.isDisconnected()) {
                continue;
            }
            Player player = session.getOnlinePlayer();
            if (player != null) {
                equipPlayerForMatch(game, player);
            }
        }
    }

    public Location getSpawnLocation(Game game, PlayerSession session) {
        return resolveSpawnLocation(game, session, false);
    }

    public Location getRespawnLocation(Game game, PlayerSession session) {
        Location cached = session.getCachedRespawnLocation();
        if (cached != null) {
            return cached.clone();
        }

        SpawnAnchor anchor = resolveSpawnAnchor(game, session);
        if (anchor == null) {
            return null;
        }

        SafeRespawnPlacement placement = SafeRespawnPlatformBuilder.findOrCreate(
                anchor.world(), anchor.x(), anchor.z(), anchor.baseY(), anchor.arena());
        if (placement.platformCreated()) {
            session.setTempRespawnBlocks(placement.snapshots());
        }
        session.setCachedRespawnLocation(placement.location());
        return placement.location().clone();
    }

    private Location resolveSpawnLocation(Game game, PlayerSession session, boolean afterDeath) {
        if (afterDeath) {
            return getRespawnLocation(game, session);
        }

        SpawnAnchor anchor = resolveSpawnAnchor(game, session);
        if (anchor == null) {
            return null;
        }
        return SpawnLocationUtil.findSafeSpawn(anchor.world(), anchor.x(), anchor.z(), anchor.baseY());
    }

    private SpawnAnchor resolveSpawnAnchor(Game game, PlayerSession session) {
        Arena arena = game.getArena();
        World world = arena.getWorld();
        if (world == null) {
            return null;
        }

        int baseY = (int) arena.getCenterY();
        int anchorX;
        int anchorZ;
        World spawnWorld = world;

        if (session.hasSpawnAnchor()) {
            anchorX = session.getSpawnAnchorX();
            anchorZ = session.getSpawnAnchorZ();
        } else {
            List<Location> spawns = arena.getSpawnPoints();
            if (spawns.isEmpty()) {
                anchorX = (int) arena.getCenterX();
                anchorZ = (int) arena.getCenterZ();
            } else {
                int index = session.getSpawnIndex() % spawns.size();
                Location anchor = spawns.get(index);
                spawnWorld = anchor.getWorld() != null ? anchor.getWorld() : world;
                anchorX = anchor.getBlockX();
                anchorZ = anchor.getBlockZ();
            }
        }

        return new SpawnAnchor(spawnWorld, anchorX, anchorZ, baseY, arena);
    }

    private record SpawnAnchor(World world, int x, int z, int baseY, Arena arena) {
    }

    public void equipPlayerForMatch(Game game, Player player) {
        PlayerSession session = game.getSession(player.getUniqueId());
        if (session == null) {
            return;
        }
        Player online = Bukkit.getPlayer(player.getUniqueId());
        if (online == null || !online.isOnline()) {
            return;
        }

        session.setAlive(true);
        session.setSpectator(false);

        Location spawn = getSpawnLocation(game, session);
        if (spawn == null || spawn.getWorld() == null) {
            plugin.getLogger().warning("Could not resolve spawn for " + online.getName()
                    + " in arena " + game.getArena().getName());
            return;
        }

        spawn.getWorld().getChunkAt(spawn);
        online.setGameMode(org.bukkit.GameMode.SURVIVAL);
        online.setAllowFlight(false);
        online.setFlying(false);
        online.setInvisible(false);
        MatchKit.clearPostMatchFlags(online);
        if (online.isDead()) {
            online.spigot().respawn();
        }
        online.teleport(spawn);
        if (game.isSimulationObserver(player.getUniqueId())) {
            plugin.getCustomItemRegistry().grantAllTestItems(online);
            plugin.getCustomItemListener().updatePassives(online);
            online.sendMessage(plugin.formatMessage("&aAll custom items granted for testing."));
            online.sendMessage(plugin.formatMessage("&7Passive items work now. Active items work once the match is &fACTIVE&7."));
            online.sendMessage(plugin.formatMessage("&7Use the &eTest Target &7zombie for combat items."));
        } else {
            MatchKit.apply(online);
        }
        MatchKit.restoreVitals(online);
    }

    public void respawnPlayerForMatch(Game game, Player player) {
        PlayerSession session = game.getSession(player.getUniqueId());
        if (session == null) {
            return;
        }
        Player online = Bukkit.getPlayer(player.getUniqueId());
        if (online == null || !online.isOnline()) {
            return;
        }

        session.setAlive(true);
        session.setSpectator(false);

        Location spawn = getRespawnLocation(game, session);
        if (spawn == null || spawn.getWorld() == null) {
            plugin.getLogger().warning("Could not resolve respawn for " + online.getName()
                    + " in arena " + game.getArena().getName());
            return;
        }

        spawn.getWorld().getChunkAt(spawn);
        online.setGameMode(org.bukkit.GameMode.SURVIVAL);
        online.setAllowFlight(false);
        online.setFlying(false);
        online.setInvisible(false);
        MatchKit.clearPostMatchFlags(online);
        if (online.isDead()) {
            online.spigot().respawn();
        }
        online.teleport(spawn);
        if (plugin.getCurseManager().isBeastCursed(session)) {
            plugin.getCurseManager().stripBeastInventory(online);
        } else {
            MatchKit.apply(online);
        }
        MatchKit.restoreVitals(online);
        plugin.getCurseManager().reapplyOnRespawn(session);
        online.setFireTicks(0);
        online.setNoDamageTicks(Math.max(online.getNoDamageTicks(), 40));
        session.clearCachedRespawnLocation();
        startRespawnGrace(game, online, session);
    }

    public void startRespawnGrace(Game game, Player player, PlayerSession session) {
        int seconds = plugin.getPluginConfig().getRespawnGraceSeconds();
        if (seconds <= 0) {
            clearRespawnGrace(game, player, session);
            return;
        }

        session.setRespawnGraceRemaining(seconds);
        player.setGlowing(true);
        broadcast(game, "&e" + player.getName() + " &7respawned with &bprotection &7for &f"
                + seconds + "s&7!");
    }

    public void clearRespawnGrace(Game game, Player player, PlayerSession session) {
        restoreTempRespawnPlatform(game, session);
        session.clearRespawnGrace();
        if (player != null && player.isOnline()) {
            player.setGlowing(false);
        }
    }

    private void restoreTempRespawnPlatform(Game game, PlayerSession session) {
        List<BlockSnapshot> snapshots = session.takeTempRespawnBlocks();
        if (snapshots.isEmpty()) {
            return;
        }
        World world = game.getArena().getWorld();
        if (world != null) {
            SafeRespawnPlatformBuilder.restore(world, snapshots);
        }
    }

    public boolean isInRespawnGrace(Game game, Player player) {
        PlayerSession session = game.getSession(player.getUniqueId());
        return session != null && session.isInRespawnGrace();
    }

    private void tickRespawnGrace(Game game) {
        for (PlayerSession session : game.getSessions().values()) {
            if (!session.isInRespawnGrace()) {
                continue;
            }
            int remaining = session.getRespawnGraceRemaining() - 1;
            session.setRespawnGraceRemaining(remaining);
            if (remaining > 0) {
                continue;
            }
            Player player = session.getPlayer();
            clearRespawnGrace(game, player, session);
            if (player != null && player.isOnline()) {
                player.sendMessage(plugin.formatMessage(
                        "&7Your respawn protection has &cexpired&7."));
            }
        }
    }

    private void tickGrace(Game game) {
        tickDisconnectedPlayers(game);
        tickRespawnGrace(game);
        plugin.getArcticManager().tick(game);
        game.setGraceRemaining(game.getGraceRemaining() - 1);
        if (game.getGraceRemaining() <= 0) {
            beginActive(game);
        }
    }

    private void beginActive(Game game) {
        game.setState(GameState.ACTIVE);
        World world = game.getArena().getWorld();
        if (world != null) {
            world.getWorldBorder().setDamageAmount(2.0);
        }
        plugin.getTerrainPhaseManager().start(game);
        broadcast(game, "&c&lFIGHT! &7PvP is now enabled!");
    }

    private void tickActive(Game game) {
        tickDisconnectedPlayers(game);
        tickRespawnGrace(game);
        plugin.getTerrainPhaseManager().tick(game);
        plugin.getPowerUpManager().tick(game);
        plugin.getSettlementManager().tick(game);
        plugin.getArcticManager().tick(game);
        plugin.getCurseManager().tick(game);
        plugin.getRageManager().tick(game);
        checkMatchTimeLimit(game);
        checkWinCondition(game);
    }

    private void checkMatchTimeLimit(Game game) {
        if (!MatchPacing.hasTimeLimit(game, plugin.getPluginConfig())) {
            return;
        }
        if (MatchPacing.getRemainingSeconds(game, plugin.getPluginConfig()) > 0) {
            return;
        }

        int living = game.countLivingTributes();
        if (!game.isSimulationMode() && living == 1) {
            game.setWinnerId(game.getLivingTributeId());
        }

        broadcast(game, "&e&lTime limit reached!");
        if (living > 1) {
            broadcast(game, "&7" + living + " players survived — no single winner.");
        } else if (living == 0) {
            broadcast(game, "&7No survivors when time ran out.");
        }
        beginEnding(game);
    }

    private void checkWinCondition(Game game) {
        if (game.isSimulationMode()) {
            return;
        }
        int living = game.countLivingTributes();
        int eliminated = game.getSessions().size() - living;

        if (living == 0) {
            beginEnding(game);
        } else if (plugin.getPluginConfig().isEndOnLastSurvivor()
                && living == 1 && eliminated > 0) {
            game.setWinnerId(game.getLivingTributeId());
            beginEnding(game);
        }
    }

    private int getMinPlayersRequired(Game game) {
        return Math.min(game.getGameMode().getMinPlayers(), plugin.getPluginConfig().getMinPlayersDefault());
    }

    public void recordPlayerDeath(Game game, Player player) {
        if (game.isSimulationMode()) {
            return;
        }
        if (game.getState() != GameState.ACTIVE && game.getState() != GameState.GRACE) {
            return;
        }

        PlayerSession session = game.getSession(player.getUniqueId());
        if (session == null || !session.isAlive() || session.isSpectator()) {
            return;
        }

        int maxDeaths = plugin.getPluginConfig().getDeathsToSpectate();
        if (maxDeaths <= 0) {
            return;
        }

        int deaths = session.incrementPlayerDeaths();
        if (deaths < maxDeaths) {
            player.sendMessage(plugin.formatMessage("&7You have &f" + (maxDeaths - deaths)
                    + " &7lives remaining."));
            return;
        }

        eliminatePlayerForDeathLimit(game, player, maxDeaths);
    }

    private void eliminatePlayerForDeathLimit(Game game, Player player, int maxDeaths) {
        PlayerSession session = game.getSession(player.getUniqueId());
        if (session == null || !session.isAlive()) {
            return;
        }
        session.setAlive(false);
        session.setSpectator(true);
        clearRespawnGrace(game, player, session);
        plugin.getCurseManager().releaseBeastForm(player);
        broadcast(game, "&c" + player.getName() + " &7has died &f" + maxDeaths
                + " &7times and is out of the match!");
        player.sendMessage(plugin.formatMessage(
                "&7You can keep watching as a spectator or use &f/hg leave &7to exit the arena."));
        checkWinCondition(game);
    }

    public void recordPlayerKill(Game game, Player killer, Player victim) {
        if (game.isSimulationMode()) {
            return;
        }
        if (game.getState() != GameState.ACTIVE) {
            return;
        }
        if (killer == null || victim == null || killer.equals(victim)) {
            return;
        }

        PlayerSession killerSession = game.getSession(killer.getUniqueId());
        PlayerSession victimSession = game.getSession(victim.getUniqueId());
        if (killerSession == null || victimSession == null) {
            return;
        }
        if (!killerSession.isAlive() || killerSession.isSpectator()) {
            return;
        }

        int killsToWin = plugin.getPluginConfig().getKillsToWin();
        if (killsToWin <= 0) {
            return;
        }

        int kills = killerSession.incrementPlayerKills();
        broadcast(game, "&c" + killer.getName() + " &7killed &c" + victim.getName()
                + " &7(&f" + kills + "&7/&f" + killsToWin + "&7 kills)");

        if (kills >= killsToWin) {
            game.setWinnerId(killer.getUniqueId());
            broadcast(game, "&6&l" + killer.getName() + " &6&lreached &f" + killsToWin + " &6&lplayer kills!");
            beginEnding(game);
        }
    }

    public void eliminatePlayer(Game game, Player player) {
        PlayerSession session = game.getSession(player.getUniqueId());
        if (session == null || !session.isAlive()) {
            return;
        }
        session.setAlive(false);
        session.setSpectator(true);
        clearRespawnGrace(game, player, session);
        plugin.getCurseManager().releaseBeastForm(player);
        player.setGameMode(org.bukkit.GameMode.SPECTATOR);
        player.setAllowFlight(true);
        player.setFlying(true);
        player.setInvisible(true);
        broadcast(game, "&c" + player.getName() + " &7has been eliminated!");
        checkWinCondition(game);
    }

    private void beginEnding(Game game) {
        if (game.getState() == GameState.ENDING || game.getState() == GameState.RESET || game.isResetStarted()) {
            return;
        }
        game.setState(GameState.ENDING);
        plugin.getCurseManager().clear(game);

        if (game.getWinnerId() != null) {
            UUID winnerId = game.getWinnerId();
            PlayerSession winnerSession = game.getSession(winnerId);
            String winnerName = winnerSession != null ? winnerSession.getPlayerName() : null;
            Player winner = Bukkit.getPlayer(winnerId);
            if (winnerName == null && winner != null) {
                winnerName = winner.getName();
            }
            if (winnerName != null) {
                broadcast(game, "&6&l" + winnerName + " &6&lwins the Hunger Games!");
            }
            if (winner != null && winner.isOnline()) {
                spawnFireworks(winner.getLocation());
            }
        } else {
            broadcast(game, "&7No winner — everyone was eliminated.");
        }
        if (plugin.getVolcanoComet().tryImpact(game, () -> {
            broadcast(game, "&7Resetting the arena...");
            startArenaReset(game);
        })) {
            return;
        }
        broadcast(game, "&7Resetting the arena...");
        startArenaReset(game);
    }

    private void tickEnding(Game game) {
        if (plugin.getVolcanoComet().isImpacting(game)) {
            return;
        }
        startArenaReset(game);
    }

    private void tickReset(Game game) {
        startArenaReset(game);
    }

    private void startArenaReset(Game game) {
        if (game.isResetStarted()) {
            return;
        }
        game.setState(GameState.RESET);
        game.setResetStarted(true);
        cleanupGameParticipants(game);
        if (game.getArena().isGenerated()) {
            scheduleArenaTerrainReset(game);
            return;
        }
        gamesByArena.remove(game.getArena().getName().toLowerCase());
    }

    private void abandonGame(Game game) {
        abandonGame(game, shouldResetTerrain(game));
    }

    private void abandonGame(Game game, boolean resetTerrain) {
        if (game.isResetStarted()) {
            return;
        }
        if (resetTerrain && game.getArena().isGenerated()) {
            startArenaReset(game);
            return;
        }

        cleanupGameParticipants(game);

        Arena arena = game.getArena();
        if (arena.getWorld() != null) {
            World world = arena.getWorld();
            world.getWorldBorder().setSize(arena.getRadius() * 2.0);
            world.getWorldBorder().setDamageAmount(0);
        }
        gamesByArena.remove(arena.getName().toLowerCase());
    }

    private boolean shouldResetTerrain(Game game) {
        return switch (game.getState()) {
            case COUNTDOWN, GRACE, ACTIVE, ENDING, RESET -> true;
            default -> false;
        };
    }

    private void cleanupGameParticipants(Game game) {
        locatorBarManager.restore(game.getArena().getWorld());
        plugin.getGameHudManager().clearHud(game);
        restoreSimulationObserver(game);
        Arena arena = game.getArena();
        if (arena.getWorld() != null) {
            ArenaEntityUtil.clearPhaseEntities(arena.getWorld(), arena);
        }
        plugin.getTerrainPhaseManager().reset(game);
        plugin.getArcticManager().clear();
        plugin.getBlockMutationQueue().clear();
        plugin.getLootBoxManager().clearLootBoxes(game);
        plugin.getPowerUpManager().clear(game);
        plugin.getCurseManager().clear(game);
        plugin.getRageManager().clear(game);
        plugin.getVolcanoComet().destroy();

        List<UUID> participantIds = new ArrayList<>(game.getSessions().keySet());
        for (PlayerSession session : game.getSessions().values()) {
            releasePlayerFromArena(game, session);
        }
        for (UUID uuid : participantIds) {
            playerArena.remove(uuid);
        }
        game.getSessions().clear();
    }

    private void scheduleArenaTerrainReset(Game game) {
        Arena arena = game.getArena();
        String arenaKey = arena.getName().toLowerCase();
        GameMode mode = game.getGameMode();
        new ArenaGenerator(plugin).regenerateInterior(arena, mode, null, () -> {
            World world = arena.getWorld();
            if (world != null) {
                world.getWorldBorder().setSize(arena.getRadius() * 2.0);
                world.getWorldBorder().setDamageAmount(0);
            }
            gamesByArena.remove(arenaKey);
        });
    }

    private void endAndReset(Game game) {
        abandonGame(game);
    }

    private void broadcast(Game game, String message) {
        String formatted = plugin.formatMessage(message);
        for (Player player : game.getNotificationRecipients()) {
            player.sendMessage(formatted);
        }
    }

    private void spawnFireworks(Location location) {
        Firework firework = location.getWorld().spawn(location, Firework.class);
        FireworkMeta meta = firework.getFireworkMeta();
        meta.addEffect(FireworkEffect.builder()
                .withColor(org.bukkit.Color.ORANGE, org.bukkit.Color.YELLOW)
                .with(FireworkEffect.Type.BALL_LARGE)
                .build());
        meta.setPower(1);
        firework.setFireworkMeta(meta);
    }
}
