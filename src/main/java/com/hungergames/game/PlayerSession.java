package com.hungergames.game;

import com.hungergames.util.BlockSnapshot;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

public class PlayerSession {

    private final Player player;
    private boolean alive;
    private boolean spectator;
    private int playerKills;
    private int playerDeaths;
    private ItemStack[] savedInventory;
    private ItemStack[] savedArmor;
    private ItemStack savedOffHand;
    private Location savedLocation;
    private GameMode savedGameMode;
    private int savedTotalExperience;
    private int spawnIndex;
    private boolean hasSpawnAnchor;
    private int spawnAnchorX;
    private int spawnAnchorZ;
    private int respawnGraceRemaining;
    private Location cachedRespawnLocation;
    private final List<BlockSnapshot> tempRespawnBlocks = new ArrayList<>();

    public PlayerSession(Player player) {
        this.player = player;
        this.alive = true;
    }

    public Player getPlayer() {
        return player;
    }

    public boolean isAlive() {
        return alive;
    }

    public void setAlive(boolean alive) {
        this.alive = alive;
    }

    public boolean isSpectator() {
        return spectator;
    }

    public void setSpectator(boolean spectator) {
        this.spectator = spectator;
    }

    public int getPlayerKills() {
        return playerKills;
    }

    public int incrementPlayerKills() {
        return ++playerKills;
    }

    public int getPlayerDeaths() {
        return playerDeaths;
    }

    public int incrementPlayerDeaths() {
        return ++playerDeaths;
    }

    public int getSpawnIndex() {
        return spawnIndex;
    }

    public void setSpawnIndex(int spawnIndex) {
        this.spawnIndex = spawnIndex;
    }

    public boolean hasSpawnAnchor() {
        return hasSpawnAnchor;
    }

    public int getSpawnAnchorX() {
        return spawnAnchorX;
    }

    public int getSpawnAnchorZ() {
        return spawnAnchorZ;
    }

    public void setSpawnAnchor(int anchorX, int anchorZ) {
        this.spawnAnchorX = anchorX;
        this.spawnAnchorZ = anchorZ;
        this.hasSpawnAnchor = true;
    }

    public void clearSpawnAnchor() {
        this.hasSpawnAnchor = false;
    }

    public boolean isInRespawnGrace() {
        return respawnGraceRemaining > 0;
    }

    public int getRespawnGraceRemaining() {
        return respawnGraceRemaining;
    }

    public void setRespawnGraceRemaining(int respawnGraceRemaining) {
        this.respawnGraceRemaining = Math.max(0, respawnGraceRemaining);
    }

    public void clearRespawnGrace() {
        respawnGraceRemaining = 0;
    }

    public Location getCachedRespawnLocation() {
        return cachedRespawnLocation;
    }

    public void setCachedRespawnLocation(Location cachedRespawnLocation) {
        this.cachedRespawnLocation = cachedRespawnLocation == null ? null : cachedRespawnLocation.clone();
    }

    public void clearCachedRespawnLocation() {
        cachedRespawnLocation = null;
    }

    public void setTempRespawnBlocks(List<BlockSnapshot> blocks) {
        tempRespawnBlocks.clear();
        if (blocks != null) {
            tempRespawnBlocks.addAll(blocks);
        }
    }

    public List<BlockSnapshot> takeTempRespawnBlocks() {
        if (tempRespawnBlocks.isEmpty()) {
            return List.of();
        }
        List<BlockSnapshot> taken = new ArrayList<>(tempRespawnBlocks);
        tempRespawnBlocks.clear();
        return taken;
    }

    public boolean hasTempRespawnBlocks() {
        return !tempRespawnBlocks.isEmpty();
    }

    public void savePlayerState() {
        savedInventory = cloneItems(player.getInventory().getStorageContents());
        savedArmor = cloneItems(player.getInventory().getArmorContents());
        ItemStack offHand = player.getInventory().getItemInOffHand();
        savedOffHand = offHand == null || offHand.getType().isAir() ? null : offHand.clone();
        savedLocation = player.getLocation().clone();
        savedGameMode = player.getGameMode();
        savedTotalExperience = player.getTotalExperience();
        stripHeldItems();
    }

    public boolean hasSavedState() {
        return savedInventory != null || savedArmor != null || savedLocation != null;
    }

    public SavedPlayerState takeSavedState() {
        if (!hasSavedState()) {
            return null;
        }
        SavedPlayerState snapshot = new SavedPlayerState(
                savedInventory,
                savedArmor,
                savedOffHand,
                savedLocation,
                savedGameMode,
                savedTotalExperience
        );
        clearSavedState();
        return snapshot;
    }

    private void clearSavedState() {
        savedInventory = null;
        savedArmor = null;
        savedOffHand = null;
        savedLocation = null;
        savedGameMode = null;
        savedTotalExperience = 0;
    }

    public void stripHeldItems() {
        player.getInventory().clear();
        player.getInventory().setArmorContents(new ItemStack[4]);
        player.getInventory().setItemInOffHand(null);
    }

    /**
     * @return true if the player was online and their pre-arena state was applied
     */
    public boolean restorePlayerState() {
        if (!player.isOnline()) {
            return false;
        }
        SavedPlayerState snapshot = takeSavedState();
        if (snapshot == null) {
            return false;
        }
        applySavedState(player, snapshot);
        return true;
    }

    public static SavedPlayerState capturePlayerState(Player player) {
        ItemStack offHand = player.getInventory().getItemInOffHand();
        return new SavedPlayerState(
                cloneItems(player.getInventory().getStorageContents()),
                cloneItems(player.getInventory().getArmorContents()),
                offHand == null || offHand.getType().isAir() ? null : offHand.clone(),
                player.getLocation().clone(),
                player.getGameMode(),
                player.getTotalExperience()
        );
    }

    public static void applySavedState(Player player, SavedPlayerState snapshot) {
        if (player.isDead()) {
            player.spigot().respawn();
        }

        player.getInventory().clear();
        player.getInventory().setArmorContents(new ItemStack[4]);
        player.getInventory().setItemInOffHand(null);

        if (snapshot.inventory() != null) {
            player.getInventory().setStorageContents(cloneItems(snapshot.inventory()));
        }
        if (snapshot.armor() != null) {
            player.getInventory().setArmorContents(cloneItems(snapshot.armor()));
        }
        if (snapshot.offHand() != null) {
            player.getInventory().setItemInOffHand(snapshot.offHand().clone());
        }
        if (snapshot.location() != null) {
            player.teleport(snapshot.location());
        }
        if (snapshot.gameMode() != null) {
            player.setGameMode(snapshot.gameMode());
        }
        player.setLevel(0);
        player.setExp(0);
        player.setTotalExperience(snapshot.totalExperience());
        player.setAllowFlight(false);
        player.setFlying(false);
        player.setInvisible(false);
        player.setGlowing(false);
        MatchKit.restoreVitals(player);
    }

    private static ItemStack[] cloneItems(ItemStack[] source) {
        if (source == null) {
            return null;
        }
        ItemStack[] copy = new ItemStack[source.length];
        for (int i = 0; i < source.length; i++) {
            ItemStack item = source[i];
            copy[i] = item == null || item.getType().isAir() ? null : item.clone();
        }
        return copy;
    }
}
