package com.hungergames;

import com.hungergames.arena.ArenaGenerator;
import com.hungergames.arena.ArenaManager;
import com.hungergames.command.HgCommand;
import com.hungergames.command.HgTabCompleter;
import com.hungergames.config.PluginConfig;
import com.hungergames.game.GameHudManager;
import com.hungergames.game.GameManager;
import com.hungergames.gamemode.GameModeRegistry;
import com.hungergames.item.CustomItemRegistry;
import com.hungergames.listener.CustomItemListener;
import com.hungergames.listener.GameListener;
import com.hungergames.listener.GuardianPilotListener;
import com.hungergames.loot.LootBoxManager;
import com.hungergames.powerup.PowerUpManager;
import com.hungergames.powerup.PowerUpRegistry;
import com.hungergames.terrain.BlockMutationQueue;
import com.hungergames.terrain.LavaFlowAccelerator;
import com.hungergames.terrain.TerrainPhaseManager;
import com.hungergames.util.ColorUtil;
import org.bukkit.plugin.java.JavaPlugin;

public class HungerGamesPlugin extends JavaPlugin {

    private PluginConfig pluginConfig;
    private ArenaManager arenaManager;
    private GameModeRegistry gameModeRegistry;
    private GameManager gameManager;
    private GameHudManager gameHudManager;
    private TerrainPhaseManager terrainPhaseManager;
    private LavaFlowAccelerator lavaFlowAccelerator;
    private BlockMutationQueue blockMutationQueue;
    private LootBoxManager lootBoxManager;
    private CustomItemRegistry customItemRegistry;
    private PowerUpRegistry powerUpRegistry;
    private PowerUpManager powerUpManager;
    private ArenaGenerator arenaGenerator;
    private CustomItemListener customItemListener;
    private GuardianPilotListener guardianPilotListener;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        pluginConfig = new PluginConfig(this);
        arenaManager = new ArenaManager(this);
        gameModeRegistry = new GameModeRegistry(this);
        customItemRegistry = new CustomItemRegistry(this);
        powerUpRegistry = new PowerUpRegistry(this);
        powerUpManager = new PowerUpManager(this);
        lootBoxManager = new LootBoxManager(this);
        blockMutationQueue = new BlockMutationQueue(this);
        terrainPhaseManager = new TerrainPhaseManager(this);
        lavaFlowAccelerator = new LavaFlowAccelerator(this);
        lavaFlowAccelerator.start();
        arenaGenerator = new ArenaGenerator(this);
        gameHudManager = new GameHudManager(this);
        gameManager = new GameManager(this);
        customItemListener = new CustomItemListener(this);
        guardianPilotListener = new GuardianPilotListener(this);

        HgCommand command = new HgCommand(this);
        getCommand("hg").setExecutor(command);
        getCommand("hg").setTabCompleter(new HgTabCompleter(this));

        getServer().getPluginManager().registerEvents(new GameListener(this), this);
        getServer().getPluginManager().registerEvents(customItemListener, this);
        getServer().getPluginManager().registerEvents(guardianPilotListener, this);

        getLogger().info("HungerGames enabled!");
    }

    @Override
    public void onDisable() {
        if (lavaFlowAccelerator != null) {
            lavaFlowAccelerator.shutdown();
        }
        if (gameManager != null) {
            gameManager.shutdown();
        }
        getLogger().info("HungerGames disabled.");
    }

    public void reload() {
        pluginConfig.reload();
        arenaManager.load();
        gameModeRegistry.reload();
        customItemRegistry.reload();
        powerUpRegistry.reload();
        lootBoxManager.reload();
    }

    public String formatMessage(String message) {
        return ColorUtil.color(pluginConfig.getMessagePrefix() + message);
    }

    public PluginConfig getPluginConfig() {
        return pluginConfig;
    }

    public ArenaManager getArenaManager() {
        return arenaManager;
    }

    public GameModeRegistry getGameModeRegistry() {
        return gameModeRegistry;
    }

    public GameManager getGameManager() {
        return gameManager;
    }

    public GameHudManager getGameHudManager() {
        return gameHudManager;
    }

    public TerrainPhaseManager getTerrainPhaseManager() {
        return terrainPhaseManager;
    }

    public BlockMutationQueue getBlockMutationQueue() {
        return blockMutationQueue;
    }

    public LootBoxManager getLootBoxManager() {
        return lootBoxManager;
    }

    public CustomItemRegistry getCustomItemRegistry() {
        return customItemRegistry;
    }

    public PowerUpRegistry getPowerUpRegistry() {
        return powerUpRegistry;
    }

    public PowerUpManager getPowerUpManager() {
        return powerUpManager;
    }

    public CustomItemListener getCustomItemListener() {
        return customItemListener;
    }

    public ArenaGenerator getArenaGenerator() {
        return arenaGenerator;
    }
}
