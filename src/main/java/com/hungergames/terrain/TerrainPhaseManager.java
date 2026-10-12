package com.hungergames.terrain;

import com.hungergames.HungerGamesPlugin;
import com.hungergames.arena.Arena;
import com.hungergames.game.Game;
import com.hungergames.game.GameState;
import com.hungergames.gamemode.GameMode;
import com.hungergames.gamemode.TerrainPhaseConfig;
import com.hungergames.terrain.phases.TerrainPhaseFactory;
import com.hungergames.terrain.phases.VolcanoEruptionPhase;
import com.hungergames.util.ColorUtil;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
public class TerrainPhaseManager {

    private final HungerGamesPlugin plugin;
    private TerrainPhase currentPhase;
    private TerrainPhaseConfig currentConfig;
    private int phaseIndex;
    private long phaseStartMillis;
    private long phaseEndMillis;
    private int resolvedDurationSeconds;
    private Game activeGame;
    private VolcanoBiomeWar biomeWar;
    private VolcanoBiomeFactions biomeFactions;

    public TerrainPhaseManager(HungerGamesPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Grace-time hook: freeze the volcano map to ice and remember faction
     * controllers before the FOREST/ERUPTION sequence starts.
     */
    public void onMatchBegin(Game game) {
        activeGame = game;
        resetBiomeControllers();
        if (game == null || !"volcano".equalsIgnoreCase(game.getGameMode().getInitialTerrain())) {
            return;
        }
        biomeWar = new VolcanoBiomeWar(plugin);
        biomeWar.start(game, game.getArena());
        biomeFactions = new VolcanoBiomeFactions(plugin);
    }

    public void start(Game game) {
        activeGame = game;
        phaseIndex = 0;
        beginPhase(game);
    }

    public void tick(Game game) {
        activeGame = game;
        if (currentPhase == null) {
            return;
        }
        currentPhase.onTick(game, game.getArena(), currentConfig);
        if (currentPhase.isComplete()) {
            currentPhase.onEnd(game, game.getArena(), currentConfig);
            phaseIndex++;
            if (phaseIndex < game.getGameMode().getTerrainPhases().size()) {
                beginPhase(game);
            }
        }
    }

    private void beginPhase(Game game) {
        GameMode mode = game.getGameMode();
        if (phaseIndex >= mode.getTerrainPhases().size()) {
            currentPhase = null;
            return;
        }
        currentConfig = mode.getTerrainPhases().get(phaseIndex);
        resolvedDurationSeconds = PhaseDurationResolver.resolveSeconds(
                game, plugin.getPluginConfig(), currentConfig);
        currentPhase = TerrainPhaseFactory.create(plugin, currentConfig.getType());
        phaseStartMillis = System.currentTimeMillis();
        if (isPhaseContinuous(game)) {
            phaseEndMillis = 0;
        } else {
            phaseEndMillis = phaseStartMillis + resolvedDurationSeconds * 1000L;
        }
        currentPhase.onStart(game, game.getArena(), currentConfig);

        String phaseName = currentConfig.getType().replace('_', ' ');
        broadcastToGame(game, "&e&lPhase: &f" + phaseName);
        for (Player player : game.getAlivePlayers()) {
            player.playSound(player.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 1f, 1f);
        }

        if (phaseIndex > 0) {
            plugin.getLootBoxManager().spawnPhaseDrop(game, 2 + (int) (Math.random() * 3));
        }
    }

    private void broadcastToGame(Game game, String message) {
        String formatted = plugin.formatMessage(message);
        for (Player player : game.getNotificationRecipients()) {
            player.sendMessage(formatted);
            player.sendTitle(ColorUtil.color("&6Phase Change"), ColorUtil.color(message), 10, 60, 10);
        }
    }

    public void reset(Game game) {
        if (currentPhase != null && game != null && currentConfig != null) {
            currentPhase.onEnd(game, game.getArena(), currentConfig);
        }
        currentPhase = null;
        currentConfig = null;
        phaseIndex = 0;
        phaseStartMillis = 0;
        phaseEndMillis = 0;
        resolvedDurationSeconds = 0;
        activeGame = null;
        resetBiomeControllers();
    }

    private void resetBiomeControllers() {
        if (biomeWar != null) {
            biomeWar.reset();
            biomeWar = null;
        }
        biomeFactions = null;
    }

    public VolcanoBiomeWar getBiomeWar() {
        return biomeWar;
    }

    public VolcanoBiomeFactions getBiomeFactions() {
        return biomeFactions;
    }

    public int getResolvedDurationSeconds() {
        return resolvedDurationSeconds;
    }

    public long getPhaseStartMillis() {
        return phaseStartMillis;
    }

    public String getCurrentPhaseName() {
        return currentConfig != null ? currentConfig.getType() : "NONE";
    }

    public boolean isPhaseContinuous() {
        return isPhaseContinuous(activeGame);
    }

    public boolean isPhaseContinuous(Game game) {
        if (currentConfig == null) {
            return false;
        }
        if (currentConfig.getDurationSeconds() <= 0) {
            return true;
        }
        if (game == null) {
            return false;
        }
        return phaseIndex >= game.getGameMode().getTerrainPhases().size() - 1;
    }

    public int getPhaseElapsedSeconds() {
        if (phaseStartMillis <= 0) {
            return 0;
        }
        return (int) ((System.currentTimeMillis() - phaseStartMillis) / 1000L);
    }

    public int getPhaseRemainingSeconds() {
        if (isPhaseContinuous()) {
            return -1;
        }
        if (phaseEndMillis <= 0) {
            return -1;
        }
        return Math.max(0, (int) ((phaseEndMillis - System.currentTimeMillis()) / 1000L));
    }

    public int getPhaseDurationSeconds() {
        if (currentConfig == null) {
            return 0;
        }
        if (isPhaseContinuous()) {
            return -1;
        }
        return resolvedDurationSeconds > 0 ? resolvedDurationSeconds : currentConfig.getDurationSeconds();
    }

    public TerrainPhaseConfig getCurrentPhaseConfig() {
        return currentConfig;
    }

    public java.util.Optional<VolcanoEruptionPhase> getVolcanoEruptionPhase() {
        if (currentPhase instanceof VolcanoEruptionPhase phase) {
            return java.util.Optional.of(phase);
        }
        return java.util.Optional.empty();
    }

    public boolean isVolcanoNetherAt(World world, int x, int z) {
        if (world == null) {
            return getVolcanoEruptionPhase().map(phase -> phase.isNetherConverted(x, z)).orElse(false);
        }
        return getVolcanoEruptionPhase()
                .map(phase -> phase.isNetherConvertedGround(world, x, z))
                .orElse(false);
    }

    public boolean isVolcanoForestAt(World world, int x, int z) {
        return !isVolcanoNetherAt(world, x, z);
    }

    /**
     * Hostile mobs stay off during grace and the opening FOREST phase so
     * players are not dumped into creepers, nether spawns, garrisons, or
     * ice/volcano faction armies. The gate is the current phase type, not a
     * timer — volcano/nether phases opt in by not being FOREST.
     */
    public boolean allowsHostileSpawning(Game game) {
        if (game == null || game.getState() != GameState.ACTIVE) {
            return false;
        }
        if (currentConfig == null) {
            return false;
        }
        return !"FOREST".equalsIgnoreCase(currentConfig.getType());
    }
}
