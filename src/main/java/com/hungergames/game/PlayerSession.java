package com.hungergames.game;

import com.hungergames.util.BlockSnapshot;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class PlayerSession {

    private Player player;
    private final UUID playerId;
    private final String playerName;
    private boolean alive;
    private boolean spectator;
    private boolean disconnected;
    private long disconnectedAtMillis;
    private MatchSnapshot matchSnapshot;
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
    private final List<Curse> curses = new ArrayList<>();
    private int churchOffenses;
    private long beastFormExpiresAtMillis;
    private final List<BlockSnapshot> tempRespawnBlocks = new ArrayList<>();

    public PlayerSession(Player player) {
        this.player = player;
        this.playerId = player.getUniqueId();
        this.playerName = player.getName();
        this.alive = true;
    }

    public Player getPlayer() {
        return player;
    }

    public UUID getPlayerId() {
        return playerId;
    }

    public String getPlayerName() {
        return playerName;
    }

    public Player getOnlinePlayer() {
        if (player != null && player.isOnline()) {
            return player;
        }
        Player online = Bukkit.getPlayer(playerId);
        return online != null && online.isOnline() ? online : null;
    }

    public void rebindPlayer(Player player) {
        this.player = player;
    }

    public boolean isDisconnected() {
        return disconnected;
    }

    public long getDisconnectedAtMillis() {
        return disconnectedAtMillis;
    }

    public void markDisconnected() {
        disconnected = true;
        disconnectedAtMillis = System.currentTimeMillis();
    }

    public void clearDisconnected() {
        disconnected = false;
        disconnectedAtMillis = 0L;
    }

    public boolean hasMatchSnapshot() {
        return matchSnapshot != null;
    }

    public void captureMatchSnapshot() {
        Player online = getOnlinePlayer();
        if (online == null) {
            return;
        }
        ItemStack offHand = online.getInventory().getItemInOffHand();
        matchSnapshot = new MatchSnapshot(
                cloneItems(online.getInventory().getStorageContents()),
                cloneItems(online.getInventory().getArmorContents()),
                offHand == null || offHand.getType().isAir() ? null : offHand.clone(),
                online.getLocation().clone(),
                online.getGameMode(),
                online.getHealth(),
                online.getFoodLevel(),
                online.getSaturation(),
                online.getFireTicks(),
                online.getTotalExperience(),
                online.getAbsorptionAmount(),
                List.copyOf(online.getActivePotionEffects())
        );
    }

    public boolean applyMatchSnapshot(Player target) {
        if (matchSnapshot == null || target == null || !target.isOnline()) {
            return false;
        }
        MatchSnapshot snapshot = matchSnapshot;
        if (target.isDead()) {
            target.spigot().respawn();
        }

        target.getInventory().clear();
        target.getInventory().setArmorContents(new ItemStack[4]);
        target.getInventory().setItemInOffHand(null);

        if (snapshot.inventory() != null) {
            target.getInventory().setStorageContents(cloneItems(snapshot.inventory()));
        }
        if (snapshot.armor() != null) {
            target.getInventory().setArmorContents(cloneItems(snapshot.armor()));
        }
        if (snapshot.offHand() != null) {
            target.getInventory().setItemInOffHand(snapshot.offHand().clone());
        }
        if (snapshot.gameMode() != null) {
            target.setGameMode(snapshot.gameMode());
        }
        if (snapshot.location() != null && snapshot.location().getWorld() != null) {
            snapshot.location().getWorld().getChunkAt(snapshot.location());
            target.teleport(snapshot.location());
        }

        target.setLevel(0);
        target.setExp(0);
        target.setTotalExperience(0);
        int restoredExperience = Math.max(0, snapshot.totalExperience());
        if (restoredExperience > 0) {
            target.giveExp(restoredExperience);
        }

        for (PotionEffect effect : target.getActivePotionEffects()) {
            target.removePotionEffect(effect.getType());
        }
        for (PotionEffect effect : snapshot.copiedEffects()) {
            target.addPotionEffect(effect);
        }
        return true;
    }

    public void applyMatchVitals(Player target) {
        if (matchSnapshot == null || target == null || !target.isOnline()) {
            return;
        }
        MatchSnapshot snapshot = matchSnapshot;
        double maxHealth = target.getMaxHealth();
        double health = Math.min(Math.max(0.1, snapshot.health()), maxHealth);
        target.setHealth(health);
        target.setFoodLevel(snapshot.foodLevel());
        target.setSaturation(snapshot.saturation());
        target.setExhaustion(0f);
        target.setFireTicks(Math.max(0, snapshot.fireTicks()));
        target.setAbsorptionAmount(Math.max(0d, snapshot.absorption()));
    }

    public void clearMatchSnapshot() {
        matchSnapshot = null;
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

    /** Curses branded by church condemnation; they stack and survive deaths for the match. */
    public List<Curse> getCurses() {
        return curses;
    }

    public boolean hasCurse(Curse curse) {
        return curse != null && curses.contains(curse);
    }

    public boolean hasBeastForm() {
        return getBeastCurse() != null;
    }

    public Curse getBeastCurse() {
        for (Curse curse : curses) {
            if (curse.isBeastForm()) {
                return curse;
            }
        }
        return null;
    }

    /** Most recently applied curse, or null if none. */
    public Curse getCurse() {
        return curses.isEmpty() ? null : curses.get(curses.size() - 1);
    }

    public void addCurse(Curse curse) {
        if (curse != null && !curses.contains(curse)) {
            curses.add(curse);
        }
    }

    public void removeCurse(Curse curse) {
        curses.remove(curse);
    }

    public void clearCurses() {
        curses.clear();
        churchOffenses = 0;
        clearBeastFormExpiry();
    }

    public void startBeastFormDuration(long durationMs) {
        beastFormExpiresAtMillis = System.currentTimeMillis() + Math.max(0L, durationMs);
    }

    public long getBeastFormExpiresAtMillis() {
        return beastFormExpiresAtMillis;
    }

    public boolean isBeastFormExpired() {
        return beastFormExpiresAtMillis > 0L && System.currentTimeMillis() >= beastFormExpiresAtMillis;
    }

    public void clearBeastFormExpiry() {
        beastFormExpiresAtMillis = 0L;
    }

    /** Church PvP offenses this match; each one raises the curse tier. */
    public int getChurchOffenses() {
        return churchOffenses;
    }

    public int incrementChurchOffenses() {
        return ++churchOffenses;
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
        player.setTotalExperience(0);
        int restoredExperience = Math.max(0, snapshot.totalExperience());
        if (restoredExperience > 0) {
            player.giveExp(restoredExperience);
        }
        player.setAllowFlight(false);
        player.setFlying(false);
        player.setInvisible(false);
        player.setGlowing(false);
        MatchKit.clearPostMatchFlags(player);
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
