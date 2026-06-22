package com.hungergames.loot;

import com.hungergames.HungerGamesPlugin;
import com.hungergames.arena.Arena;
import com.hungergames.arena.VolcanoCaldera;
import com.hungergames.game.Game;
import com.hungergames.game.GameState;
import com.hungergames.gamemode.GameMode;
import com.hungergames.util.ArenaScatterUtil;
import com.hungergames.util.CircleUtil;
import com.hungergames.util.SpawnLocationUtil;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.block.TileState;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

public class LootBoxManager {

    public static final NamespacedKey LOOT_BOX_KEY = new NamespacedKey("hungergames", "loot_box_id");

    private final HungerGamesPlugin plugin;
    private final LootTable lootTable;
    private final Random random = new Random();

    public LootBoxManager(HungerGamesPlugin plugin) {
        this.plugin = plugin;
        this.lootTable = new LootTable(plugin);
    }

    public LootTable getLootTable() {
        return lootTable;
    }

    public void reload() {
        lootTable.reload();
        plugin.getGameManager().getActiveGame().ifPresent(game -> {
            if (game.getState() == GameState.GRACE || game.getState() == GameState.ACTIVE) {
                prepareMatchLoot(game);
                plugin.getLogger().info("Refilled loot for active game in arena " + game.getArena().getName());
            }
        });
    }

    public void prepareMatchLoot(Game game) {
        if (usesCornucopiaChests(game.getGameMode())) {
            fillCornucopia(game);
            scatterLootBoxes(game);
        } else {
            scatterLootBoxes(game);
        }
    }

    public void scatterLootBoxes(Game game) {
        Arena arena = game.getArena();
        World world = arena.getWorld();
        if (world == null) {
            return;
        }

        boolean volcanoTerrain = isVolcanoTerrain(game.getGameMode());
        int count = computeBoxCount(arena, game.getGameMode().getLootBoxDensity());
        List<Location> locations = ArenaScatterUtil.findRandomLocations(
                plugin, arena, count, null, null, volcanoTerrain);
        String tier = game.getGameMode().getLootTier();

        int placed = 0;
        for (Location loc : locations) {
            placeLootBox(game, loc, tier);
            placed++;
        }

        if (placed < count) {
            plugin.getLogger().warning("Only placed " + placed + " of " + count
                    + " scattered loot chest(s) in arena " + arena.getName() + ".");
        }
    }

    public void spawnPhaseDrop(Game game, int count) {
        Arena arena = game.getArena();
        boolean volcanoTerrain = isVolcanoTerrain(game.getGameMode());
        List<Location> locations = ArenaScatterUtil.findRandomLocations(
                plugin, arena, count, null, null, volcanoTerrain);
        String tier = game.getGameMode().getLootTier();
        for (Player player : game.getAlivePlayers()) {
            player.sendMessage(plugin.formatMessage("&d&lSupply drop incoming!"));
        }
        for (Location loc : locations) {
            placeLootBox(game, loc, tier);
        }
    }

    public LootBox placeLootBox(Game game, Location location, String tier) {
        Material blockType = plugin.getPluginConfig().getLootBoxBlockType();
        ensureChunkLoaded(location);
        Block block = location.getBlock();
        if (SpawnLocationUtil.isLootChestReplaceable(block.getType()) && !block.getType().isAir()) {
            block.setType(Material.AIR, false);
        }
        block.setType(blockType, false);

        UUID id = UUID.randomUUID();
        LootBox lootBox = new LootBox(id, location.clone(), tier, blockType);
        game.getActiveLootBoxes().put(id, lootBox);

        if (block.getState() instanceof TileState state) {
            state.getPersistentDataContainer().set(LOOT_BOX_KEY, PersistentDataType.STRING, id.toString());
            state.update(true, false);
        }

        if (block.getState() instanceof Container) {
            List<ItemStack> loot = lootTable.roll(tier, false);
            fillContainerBlock(block, loot);
        } else {
            plugin.getLogger().warning("Loot chest block type " + blockType
                    + " is not a container; loot will not be placed inside.");
        }

        return lootBox;
    }

    public void markLootBoxOpened(Player player, Block block) {
        Optional<UUID> idOpt = getLootBoxId(block);
        if (idOpt.isEmpty()) {
            return;
        }

        plugin.getGameManager().getPlayerGame(player).ifPresent(game -> {
            LootBox box = game.getActiveLootBoxes().get(idOpt.get());
            if (box == null || box.isOpened()) {
                return;
            }
            if (game.getState() != GameState.ACTIVE && game.getState() != GameState.GRACE) {
                return;
            }

            box.setOpened(true);
            player.playSound(player.getLocation(), Sound.BLOCK_CHEST_OPEN, 1f, 1.2f);
            player.spawnParticle(Particle.HAPPY_VILLAGER, block.getLocation().add(0.5, 0.5, 0.5), 12);
        });
    }

    public void tryRemoveLootBoxOnBreak(Block block, Game game) {
        getLootBoxId(block).ifPresent(id -> game.getActiveLootBoxes().remove(id));
    }

    public boolean isLootBoxOpened(Block block, Game game) {
        return getLootBoxId(block)
                .map(id -> game.getActiveLootBoxes().get(id))
                .map(LootBox::isOpened)
                .orElse(false);
    }

    public int fillCornucopia(Game game) {
        return fillCornucopiaChests(game.getArena(), game.getGameMode().getLootTier());
    }

    public int fillCornucopiaChests(Arena arena, String tier) {
        World world = arena.getWorld();
        if (world == null) {
            return 0;
        }

        GameMode mode = plugin.getGameModeRegistry().getMode(arena.getGameModeId()).orElse(null);
        if (mode != null && !usesCornucopiaChests(mode)) {
            return 0;
        }

        List<Block> chestBlocks = findCornucopiaChests(arena);
        if (chestBlocks.isEmpty()) {
            plugin.getLogger().warning("No cornucopia chests found near center of arena " + arena.getName()
                    + ". Regenerate the arena or check that chest blocks exist.");
            return 0;
        }

        int filled = 0;
        for (Block block : chestBlocks) {
            List<ItemStack> loot = lootTable.roll(tier, true);
            if (fillContainerBlock(block, loot)) {
                filled++;
            }
        }

        plugin.getLogger().info("Filled " + filled + " cornucopia chest(s) in arena " + arena.getName()
                + " (tier: " + tier + ").");
        return filled;
    }

    public int refillCornucopiaForArena(String arenaName) {
        Arena arena = plugin.getArenaManager().getArena(arenaName).orElse(null);
        if (arena == null) {
            return -1;
        }
        GameMode mode = plugin.getGameModeRegistry().getMode(arena.getGameModeId()).orElse(null);
        String tier = mode != null ? mode.getLootTier() : "normal";
        return fillCornucopiaChests(arena, tier);
    }

    public boolean tryRefillEmptyCornucopiaChest(Player player, Block block) {
        if (isLootBox(block)) {
            return false;
        }
        if (block.getType() != Material.CHEST && block.getType() != Material.TRAPPED_CHEST) {
            return false;
        }

        return plugin.getGameManager().getPlayerGame(player).map(game -> {
            if (game.getState() != GameState.GRACE && game.getState() != GameState.ACTIVE) {
                return false;
            }
            if (!isCornucopiaChest(block, game.getArena())) {
                return false;
            }
            if (!(block.getState() instanceof Container container)) {
                return false;
            }
            if (!isInventoryEmpty(container)) {
                return false;
            }

            List<ItemStack> loot = lootTable.roll(game.getGameMode().getLootTier(), true);
            return fillContainerBlock(block, loot);
        }).orElse(false);
    }

    public void clearLootBoxes(Game game) {
        for (LootBox box : new ArrayList<>(game.getActiveLootBoxes().values())) {
            Block block = box.getLocation().getBlock();
            if (block.getType() == box.getBlockType()) {
                block.setType(Material.AIR, false);
            }
        }
        game.getActiveLootBoxes().clear();
    }

    private boolean isCornucopiaChest(Block block, Arena arena) {
        int searchRadius = Math.max(12, VolcanoCaldera.RIM_RADIUS + 5);
        double cx = arena.getCenterX();
        double cz = arena.getCenterZ();
        return CircleUtil.distanceFromCenter(cx, cz, block.getX(), block.getZ()) <= searchRadius;
    }

    private boolean isInventoryEmpty(Container container) {
        for (ItemStack stack : container.getInventory().getContents()) {
            if (stack != null && !stack.getType().isAir()) {
                return false;
            }
        }
        return true;
    }

    private List<Block> findCornucopiaChests(Arena arena) {
        World world = arena.getWorld();
        if (world == null) {
            return List.of();
        }

        int cx = (int) arena.getCenterX();
        int cz = (int) arena.getCenterZ();
        int baseY = (int) arena.getCenterY();
        int searchRadius = Math.max(12, VolcanoCaldera.RIM_RADIUS + 5);
        int minY = baseY - 8;
        int maxY = baseY + 48;

        for (int x = cx - searchRadius; x <= cx + searchRadius; x++) {
            for (int z = cz - searchRadius; z <= cz + searchRadius; z++) {
                ensureChunkLoaded(world, x, z);
            }
        }

        List<Block> chests = new ArrayList<>();
        for (int x = cx - searchRadius; x <= cx + searchRadius; x++) {
            for (int z = cz - searchRadius; z <= cz + searchRadius; z++) {
                for (int y = minY; y <= maxY; y++) {
                    Block block = world.getBlockAt(x, y, z);
                    Material type = block.getType();
                    if ((type == Material.CHEST || type == Material.TRAPPED_CHEST)
                            && !isLootBox(block)) {
                        chests.add(block);
                    }
                }
            }
        }
        return chests;
    }

    private boolean fillContainerBlock(Block block, List<ItemStack> loot) {
        ensureChunkLoaded(block.getLocation());
        if (!(block.getState() instanceof Container container)) {
            return false;
        }

        container.getInventory().clear();
        if (loot.isEmpty()) {
            plugin.getLogger().warning("Loot roll returned no items for chest at "
                    + block.getX() + ", " + block.getY() + ", " + block.getZ()
                    + ". Check plugins/HungerGames/loot/tables.yml and loot_boxes.yml.");
            container.update(true, false);
            return false;
        }

        for (ItemStack item : loot) {
            if (item != null && !item.getType().isAir()) {
                container.getInventory().addItem(item.clone());
            }
        }
        container.update(true, false);
        return true;
    }

    private int computeBoxCount(Arena arena, String density) {
        double multiplier = plugin.getPluginConfig().getDensityMultiplier(density);
        double per100 = plugin.getPluginConfig().getLootBoxDensityPer100Radius();
        return Math.max(4, (int) ((arena.getRadius() / 100.0) * per100 * multiplier));
    }

    public boolean usesCornucopiaChests(GameMode mode) {
        return !isVolcanoTerrain(mode);
    }

    private boolean isVolcanoTerrain(GameMode mode) {
        return mode != null && "volcano".equalsIgnoreCase(mode.getInitialTerrain());
    }

    private void ensureChunkLoaded(Location location) {
        World world = location.getWorld();
        if (world != null) {
            ensureChunkLoaded(world, location.getBlockX(), location.getBlockZ());
        }
    }

    private void ensureChunkLoaded(World world, int x, int z) {
        Chunk chunk = world.getChunkAt(x >> 4, z >> 4);
        if (!chunk.isLoaded()) {
            chunk.load(true);
        }
    }

    public boolean isLootBox(Block block) {
        return getLootBoxId(block).isPresent();
    }

    private Optional<UUID> getLootBoxId(Block block) {
        if (!(block.getState() instanceof TileState state)) {
            return Optional.empty();
        }
        if (!state.getPersistentDataContainer().has(LOOT_BOX_KEY, PersistentDataType.STRING)) {
            return Optional.empty();
        }
        String idStr = state.getPersistentDataContainer().get(LOOT_BOX_KEY, PersistentDataType.STRING);
        return Optional.of(UUID.fromString(idStr));
    }
}
