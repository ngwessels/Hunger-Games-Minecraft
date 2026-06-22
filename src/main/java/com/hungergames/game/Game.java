package com.hungergames.game;

import com.hungergames.arena.Arena;
import com.hungergames.gamemode.GameMode;
import com.hungergames.loot.LootBox;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class Game {

    private final Arena arena;
    private final GameMode gameMode;
    private GameState state = GameState.LOBBY;
    private final Map<UUID, PlayerSession> sessions = new HashMap<>();
    private final Map<UUID, LootBox> activeLootBoxes = new HashMap<>();
    private int countdownRemaining;
    private int graceRemaining;
    private UUID winnerId;
    private long matchStartMillis;
    private boolean resetStarted;
    private boolean simulationMode;
    private UUID simulationObserverId;
    private SavedPlayerState simulationObserverState;

    public Game(Arena arena, GameMode gameMode) {
        this.arena = arena;
        this.gameMode = gameMode;
    }

    public Arena getArena() {
        return arena;
    }

    public GameMode getGameMode() {
        return gameMode;
    }

    public GameState getState() {
        return state;
    }

    public void setState(GameState state) {
        this.state = state;
    }

    public Map<UUID, PlayerSession> getSessions() {
        return sessions;
    }

    public Map<UUID, LootBox> getActiveLootBoxes() {
        return activeLootBoxes;
    }

    public void addPlayer(Player player) {
        PlayerSession session = new PlayerSession(player);
        session.savePlayerState();
        sessions.put(player.getUniqueId(), session);
    }

    public void removePlayer(UUID uuid) {
        sessions.remove(uuid);
    }

    public PlayerSession getSession(UUID uuid) {
        return sessions.get(uuid);
    }

    public List<Player> getAlivePlayers() {
        List<Player> alive = new ArrayList<>();
        for (PlayerSession session : sessions.values()) {
            if (session.isAlive() && !session.isSpectator()) {
                alive.add(session.getPlayer());
            }
        }
        return alive;
    }

    public List<Player> getAllParticipants() {
        List<Player> players = new ArrayList<>();
        for (PlayerSession session : sessions.values()) {
            players.add(session.getPlayer());
        }
        return players;
    }

    public List<Player> getNotificationRecipients() {
        List<Player> recipients = new ArrayList<>(getAllParticipants());
        if (simulationObserverId != null) {
            Player observer = Bukkit.getPlayer(simulationObserverId);
            if (observer != null && observer.isOnline() && !recipients.contains(observer)) {
                recipients.add(observer);
            }
        }
        return recipients;
    }

    public boolean isSimulationMode() {
        return simulationMode;
    }

    public void setSimulationMode(boolean simulationMode) {
        this.simulationMode = simulationMode;
    }

    public UUID getSimulationObserverId() {
        return simulationObserverId;
    }

    public void setSimulationObserverId(UUID simulationObserverId) {
        this.simulationObserverId = simulationObserverId;
    }

    public SavedPlayerState getSimulationObserverState() {
        return simulationObserverState;
    }

    public void setSimulationObserverState(SavedPlayerState simulationObserverState) {
        this.simulationObserverState = simulationObserverState;
    }

    public int getCountdownRemaining() {
        return countdownRemaining;
    }

    public void setCountdownRemaining(int countdownRemaining) {
        this.countdownRemaining = countdownRemaining;
    }

    public int getGraceRemaining() {
        return graceRemaining;
    }

    public void setGraceRemaining(int graceRemaining) {
        this.graceRemaining = graceRemaining;
    }

    public UUID getWinnerId() {
        return winnerId;
    }

    public void setWinnerId(UUID winnerId) {
        this.winnerId = winnerId;
    }

    public boolean isParticipant(UUID uuid) {
        return sessions.containsKey(uuid);
    }

    public boolean isSimulationObserver(UUID uuid) {
        return simulationMode && simulationObserverId != null && simulationObserverId.equals(uuid);
    }

    public boolean allowsJoin() {
        return state == GameState.LOBBY;
    }

    public long getMatchStartMillis() {
        return matchStartMillis;
    }

    public void setMatchStartMillis(long matchStartMillis) {
        this.matchStartMillis = matchStartMillis;
    }

    public int getMatchElapsedSeconds() {
        if (matchStartMillis <= 0) {
            return 0;
        }
        return (int) ((System.currentTimeMillis() - matchStartMillis) / 1000L);
    }

    public boolean isResetStarted() {
        return resetStarted;
    }

    public void setResetStarted(boolean resetStarted) {
        this.resetStarted = resetStarted;
    }
}
