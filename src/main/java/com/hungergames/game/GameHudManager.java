package com.hungergames.game;

import com.hungergames.HungerGamesPlugin;
import com.hungergames.util.ColorUtil;
import org.bukkit.Bukkit;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public class GameHudManager {

    private final HungerGamesPlugin plugin;
    private final Map<String, BossBar> bossBarsByArena = new HashMap<>();

    public GameHudManager(HungerGamesPlugin plugin) {
        this.plugin = plugin;
    }

    public void update(Game game) {
        String arenaKey = game.getArena().getName().toLowerCase();
        BossBar bossBar = bossBarsByArena.computeIfAbsent(arenaKey, key ->
                Bukkit.createBossBar("", BarColor.YELLOW, BarStyle.SEGMENTED_10));

        syncPlayers(game, bossBar);

        switch (game.getState()) {
            case COUNTDOWN -> updateCountdown(game, bossBar);
            case GRACE -> updateGrace(game, bossBar);
            case ACTIVE -> updateActive(game, bossBar);
            case ENDING -> updateEnding(game, bossBar);
            default -> clearHud(game);
        }
    }

    private void updateCountdown(Game game, BossBar bossBar) {
        int remaining = game.getCountdownRemaining();
        bossBar.setTitle(ColorUtil.color("&eStarting in &f" + remaining + "s"));
        bossBar.setProgress(Math.max(0, Math.min(1, remaining / (double) plugin.getPluginConfig().getDefaultCountdown())));
        bossBar.setColor(BarColor.GREEN);
        updateScoreboard(game, "Countdown", remaining + "s", "-", game.countLivingTributes(), "-");
    }

    private void updateGrace(Game game, BossBar bossBar) {
        int remaining = game.getGraceRemaining();
        int total = game.getGameMode().getGracePeriodSeconds();
        bossBar.setTitle(ColorUtil.color(
                "&aGrace Period &7| &fPvP Off &7| &e" + formatTime(remaining) + " remaining"
                        + " &7| Match: &f" + formatMatchTimeLabel(game)));
        bossBar.setProgress(matchTimeProgress(game, total > 0 ? remaining / (double) total : 0));
        bossBar.setColor(BarColor.BLUE);
        updateScoreboard(game, "Grace", formatTime(remaining), formatMatchTimeLabel(game), game.countLivingTributes(), "-");
    }

    private void updateActive(Game game, BossBar bossBar) {
        long elapsedSec = game.getMatchElapsedSeconds();
        int phaseRemaining = plugin.getTerrainPhaseManager().getPhaseRemainingSeconds();
        int phaseElapsed = plugin.getTerrainPhaseManager().getPhaseElapsedSeconds();
        int phaseTotal = plugin.getTerrainPhaseManager().getPhaseDurationSeconds();
        boolean continuous = plugin.getTerrainPhaseManager().isPhaseContinuous(game);
        String phaseName = plugin.getTerrainPhaseManager().getCurrentPhaseName().replace('_', ' ');
        int alive = game.countLivingTributes();
        String killsLabel = formatKillsLeaderLabel(game);

        String phaseTimeLabel = continuous
                ? "Active: &f" + formatTime(phaseElapsed)
                : "Next phase: &f" + formatTime(phaseRemaining);

        bossBar.setTitle(ColorUtil.color(
                "&6" + phaseName
                        + " &7| Match: &f" + formatMatchTimeLabel(game)
                        + " &7| " + phaseTimeLabel
                        + " &7| Alive: &c" + alive
                        + " &7| " + killsLabel));

        if (MatchPacing.hasTimeLimit(game, plugin.getPluginConfig())) {
            bossBar.setProgress(matchTimeProgress(game, 1.0));
            bossBar.setColor(BarColor.RED);
        } else if (continuous) {
            bossBar.setProgress(0.5 + 0.5 * Math.sin(phaseElapsed * 0.15));
            bossBar.setColor(BarColor.RED);
        } else {
            bossBar.setProgress(phaseTotal > 0 ? phaseRemaining / (double) phaseTotal : 1.0);
            bossBar.setColor(BarColor.RED);
        }

        String scoreboardPhaseTime = continuous ? formatTime(phaseElapsed) : formatTime(phaseRemaining);
        updateScoreboard(game, phaseName, scoreboardPhaseTime, formatMatchTimeLabel(game), alive, killsLabel);
    }

    private void updateEnding(Game game, BossBar bossBar) {
        if (plugin.getVolcanoComet().isImpacting(game)) {
            bossBar.setTitle(ColorUtil.color("&c&lCOMET IMPACT &7| &6The arena is being destroyed"));
            bossBar.setProgress(1.0);
            bossBar.setColor(BarColor.RED);
            return;
        }
        bossBar.setTitle(ColorUtil.color("&6&lMatch Over &7| &eResetting arena..."));
        bossBar.setProgress(1.0);
        bossBar.setColor(BarColor.PURPLE);
    }

    private void updateScoreboard(Game game, String phase, String phaseTime, String matchTime, int alive,
                                  String killsLabel) {
        for (Player player : game.getAllParticipants()) {
            if (!player.isOnline()) {
                continue;
            }
            Scoreboard board = Bukkit.getScoreboardManager().getNewScoreboard();
            Objective obj = board.registerNewObjective("hg", Criteria.DUMMY, ColorUtil.color("&6&lHunger Games"));
            obj.setDisplaySlot(DisplaySlot.SIDEBAR);
            obj.getScore(ColorUtil.color("&7Phase: &f" + phase)).setScore(6);
            obj.getScore(ColorUtil.color("&7Phase time: &f" + phaseTime)).setScore(5);
            obj.getScore(ColorUtil.color("&7Match time: &f" + matchTime)).setScore(4);
            obj.getScore(ColorUtil.color("&7Alive: &c" + alive)).setScore(3);
            obj.getScore(ColorUtil.color("&7" + killsLabel)).setScore(2);
            obj.getScore(ColorUtil.color("&8 ")).setScore(1);
            player.setScoreboard(board);
        }
    }

    private String formatKillsLeaderLabel(Game game) {
        int killsToWin = plugin.getPluginConfig().getKillsToWin();
        if (killsToWin <= 0) {
            return "Kills: &7off";
        }

        PlayerSession leader = null;
        for (PlayerSession session : game.getSessions().values()) {
            if (leader == null || session.getPlayerKills() > leader.getPlayerKills()) {
                leader = session;
            }
        }

        if (leader == null || leader.getPlayerKills() == 0) {
            return "Kills: &f0&7/&f" + killsToWin;
        }

        return "Leader: &f" + leader.getPlayerName()
                + " &7(&f" + leader.getPlayerKills() + "&7/&f" + killsToWin + "&7)";
    }

    private void syncPlayers(Game game, BossBar bossBar) {
        for (Player player : game.getAllParticipants()) {
            if (!bossBar.getPlayers().contains(player)) {
                bossBar.addPlayer(player);
            }
        }
        for (Player player : new ArrayList<>(bossBar.getPlayers())) {
            PlayerSession session = game.getSession(player.getUniqueId());
            if (session == null || session.isDisconnected() || !player.isOnline()) {
                bossBar.removePlayer(player);
            }
        }
    }

    public void clearHud(Game game) {
        String arenaKey = game.getArena().getName().toLowerCase();
        BossBar bossBar = bossBarsByArena.remove(arenaKey);

        Set<Player> players = new LinkedHashSet<>(game.getNotificationRecipients());
        if (bossBar != null) {
            players.addAll(bossBar.getPlayers());
            bossBar.removeAll();
        }

        for (Player player : players) {
            resetScoreboard(player);
        }
    }

    public void clearHudForPlayer(Player player, Game game) {
        if (player == null || !player.isOnline()) {
            return;
        }
        String arenaKey = game.getArena().getName().toLowerCase();
        BossBar bossBar = bossBarsByArena.get(arenaKey);
        if (bossBar != null) {
            bossBar.removePlayer(player);
        }
        resetScoreboard(player);
    }

    public void clearHudForPlayer(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        for (BossBar bar : bossBarsByArena.values()) {
            bar.removePlayer(player);
        }
        resetScoreboard(player);
    }

    private void resetScoreboard(Player player) {
        if (player != null && player.isOnline()) {
            player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        }
    }

    public void clearAll() {
        for (BossBar bar : bossBarsByArena.values()) {
            bar.removeAll();
        }
        bossBarsByArena.clear();
    }

    private String formatTime(int totalSeconds) {
        int minutes = totalSeconds / 60;
        int seconds = totalSeconds % 60;
        return String.format("%d:%02d", minutes, seconds);
    }

    private String formatMatchTimeLabel(Game game) {
        int remaining = MatchPacing.getRemainingSeconds(game, plugin.getPluginConfig());
        if (remaining >= 0) {
            return formatTime(remaining) + " left";
        }
        return formatTime(game.getMatchElapsedSeconds());
    }

    private double matchTimeProgress(Game game, double fallback) {
        int max = MatchPacing.getMaxMatchSeconds(game, plugin.getPluginConfig());
        if (max <= 0) {
            return fallback;
        }
        return MatchPacing.getRemainingSeconds(game, plugin.getPluginConfig()) / (double) max;
    }
}
