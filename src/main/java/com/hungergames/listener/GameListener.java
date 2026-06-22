package com.hungergames.listener;

import com.hungergames.HungerGamesPlugin;
import com.hungergames.arena.Arena;
import com.hungergames.game.Game;
import com.hungergames.game.GameState;
import com.hungergames.game.PlayerSession;
import com.hungergames.terrain.VolcanoUndergroundGuard;
import com.hungergames.util.ArenaBounds;
import com.hungergames.util.ArenaEntityUtil;
import com.hungergames.util.CircleUtil;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.EnumSet;
import java.util.Set;

public class GameListener implements Listener {

    private static final Set<Material> PROTECTED_BLOCKS = EnumSet.of(
            Material.OBSIDIAN,
            Material.BEDROCK,
            Material.CHEST,
            Material.BARREL,
            Material.ENDER_CHEST,
            Material.GOLD_BLOCK
    );

    private final HungerGamesPlugin plugin;

    public GameListener(HungerGamesPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        plugin.getGameManager().getPlayerGame(player).ifPresent(game -> {
            if (game.getState() == GameState.ACTIVE || game.getState() == GameState.GRACE) {
                event.getDrops().clear();
                event.setDroppedExp(0);
            }
            if (game.getState() == GameState.ACTIVE) {
                Player killer = resolvePlayerKiller(player);
                if (killer != null) {
                    plugin.getGameManager().recordPlayerKill(game, killer, player);
                }
            }
            if (game.getState() == GameState.ACTIVE || game.getState() == GameState.GRACE) {
                plugin.getGameManager().recordPlayerDeath(game, player);
            }
        });
    }

    private Player resolvePlayerKiller(Player victim) {
        Player killer = victim.getKiller();
        if (killer != null) {
            return killer;
        }
        if (victim.getLastDamageCause() instanceof EntityDamageByEntityEvent damage) {
            return extractPlayerDamager(damage.getDamager());
        }
        return null;
    }

    private Player extractPlayerDamager(Entity damager) {
        if (damager instanceof Player player) {
            return player;
        }
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player player) {
            return player;
        }
        return null;
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        plugin.getGameManager().getPlayerGame(player).ifPresent(game -> {
            if (game.getState() != GameState.ACTIVE && game.getState() != GameState.GRACE) {
                return;
            }
            var session = game.getSession(player.getUniqueId());
            if (session == null) {
                return;
            }
            if (session.isSpectator() || !session.isAlive()) {
                Location spectatorSpawn = plugin.getGameManager().resolveSpectatorSpawn(game);
                if (spectatorSpawn != null) {
                    event.setRespawnLocation(spectatorSpawn);
                }
                plugin.getServer().getScheduler().runTask(plugin, () ->
                        plugin.getGameManager().respawnPlayerAsSpectator(game, player));
                return;
            }
            event.setRespawnLocation(plugin.getGameManager().getRespawnLocation(game, session));
            plugin.getServer().getScheduler().runTask(plugin, () ->
                    plugin.getGameManager().respawnPlayerForMatch(game, player));
        });
    }

    @EventHandler
    public void onFoodLevelChange(FoodLevelChangeEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        plugin.getGameManager().getPlayerGame(player).ifPresent(game -> {
            if (game.getState() != GameState.ACTIVE && game.getState() != GameState.GRACE) {
                return;
            }
            if (event.getFoodLevel() < player.getFoodLevel()) {
                event.setCancelled(true);
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onRegainHealth(EntityRegainHealthEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        double multiplier = plugin.getPluginConfig().getMatchHealMultiplier();
        if (multiplier <= 1.0) {
            return;
        }
        plugin.getGameManager().getPlayerGame(player).ifPresent(game -> {
            if (game.getState() != GameState.ACTIVE && game.getState() != GameState.GRACE) {
                return;
            }
            PlayerSession session = game.getSession(player.getUniqueId());
            if (session == null || !session.isAlive() || session.isSpectator()) {
                return;
            }
            event.setAmount(event.getAmount() * multiplier);
        });
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        plugin.getGameManager().applyPendingRestore(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        plugin.getGameManager().getPlayerGame(player).ifPresent(game -> {
            if (game.getState() == GameState.ACTIVE) {
                PlayerSession session = game.getSession(player.getUniqueId());
                if (session != null && session.isAlive()) {
                    plugin.getGameManager().eliminatePlayer(game, player);
                }
            }
            plugin.getGameManager().leaveGame(player);
        });
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPvP(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        Player damager = null;
        if (event.getDamager() instanceof Player p) {
            damager = p;
        }
        if (damager == null) {
            return;
        }

        var victimGame = plugin.getGameManager().getPlayerGame(victim);
        var damagerGame = plugin.getGameManager().getPlayerGame(damager);
        if (victimGame.isEmpty() || damagerGame.isEmpty()) {
            return;
        }
        Game game = victimGame.get();
        if (!game.equals(damagerGame.get())) {
            event.setCancelled(true);
            return;
        }
        if (game.getState() == GameState.GRACE) {
            event.setCancelled(true);
            return;
        }
        if (plugin.getGameManager().isInRespawnGrace(game, victim)
                || plugin.getGameManager().isInRespawnGrace(game, damager)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onVolcanoGuardianDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof LivingEntity living
                && ArenaEntityUtil.isVolcanoGuardian(living)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        plugin.getGameManager().getPlayerGame(player).ifPresent(game -> {
            if (game.getState() != GameState.ACTIVE && game.getState() != GameState.GRACE) {
                return;
            }
            if (plugin.getGameManager().isInRespawnGrace(game, player)) {
                event.setCancelled(true);
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event.getTo() == null) {
            return;
        }
        if (event.getFrom().getBlockX() == event.getTo().getBlockX()
                && event.getFrom().getBlockZ() == event.getTo().getBlockZ()) {
            return;
        }
        Player player = event.getPlayer();
        plugin.getGameManager().getPlayerGame(player).ifPresent(game -> {
            Arena arena = game.getArena();
            if (arena.getWorld() == null || !player.getWorld().equals(arena.getWorld())) {
                return;
            }
            Location to = event.getTo();
            if (ArenaBounds.isInsidePlayArea(arena, to.getX(), to.getZ())) {
                return;
            }
            event.setTo(ArenaBounds.clampToPlayArea(arena, to));
        });
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (plugin.getLootBoxManager().isLootBox(block)) {
            var playerGame = plugin.getGameManager().getPlayerGame(event.getPlayer());
            if (playerGame.isPresent()) {
                Game game = playerGame.get();
                if (!plugin.getLootBoxManager().isLootBoxOpened(block, game)) {
                    event.setCancelled(true);
                    return;
                }
                plugin.getLootBoxManager().tryRemoveLootBoxOnBreak(block, game);
            } else {
                event.setCancelled(true);
            }
            return;
        }
        if (PROTECTED_BLOCKS.contains(block.getType())) {
            plugin.getGameManager().getPlayerGame(event.getPlayer()).ifPresent(game -> {
                if (isInArenaWorld(event.getPlayer(), game)) {
                    event.setCancelled(true);
                }
            });
            return;
        }

        plugin.getGameManager().getPlayerGame(event.getPlayer()).ifPresent(game -> {
            if (!isInArenaWorld(event.getPlayer(), game)) {
                return;
            }
            if (game.getState() != GameState.ACTIVE && game.getState() != GameState.GRACE) {
                event.setCancelled(true);
                return;
            }
            VolcanoUndergroundGuard.handleBlockBreak(plugin, game, event.getBlock(), event.getPlayer());
        });
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        plugin.getGameManager().getPlayerGame(event.getPlayer()).ifPresent(game -> {
            if (!isInArenaWorld(event.getPlayer(), game)) {
                return;
            }
            if (game.getState() != GameState.ACTIVE && game.getState() != GameState.GRACE) {
                event.setCancelled(true);
            }
        });
    }

    private boolean isInArenaWorld(Player player, Game game) {
        return game.getArena().getWorld() != null
                && player.getWorld().equals(game.getArena().getWorld());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onLootBoxOpen(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block.getType() == Material.CHEST || block.getType() == Material.TRAPPED_CHEST) {
            plugin.getLootBoxManager().tryRefillEmptyCornucopiaChest(event.getPlayer(), block);
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onCornucopiaChestOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        if (!(event.getInventory().getHolder() instanceof org.bukkit.block.BlockState state)) {
            return;
        }
        Block block = state.getBlock();
        if (plugin.getLootBoxManager().isLootBox(block)) {
            plugin.getLootBoxManager().markLootBoxOpened(player, block);
            return;
        }
        plugin.getLootBoxManager().tryRefillEmptyCornucopiaChest(player, block);
    }

    @EventHandler
    public void onExplode(EntityExplodeEvent event) {
        plugin.getGameManager().getActiveGame().ifPresent(game -> {
            Arena arena = game.getArena();
            if (!event.getLocation().getWorld().equals(arena.getWorld())) {
                return;
            }
            if (event.getEntity() instanceof TNTPrimed tnt && ArenaEntityUtil.isVolcanoTnt(tnt)) {
                event.blockList().removeIf(block -> block.getType() == Material.NETHERRACK
                        || isExplosionProtected(block, arena));
                return;
            }
            event.blockList().clear();
        });
    }

    private boolean isExplosionProtected(Block block, Arena arena) {
        Material type = block.getType();
        if (PROTECTED_BLOCKS.contains(type)) {
            return true;
        }
        if (plugin.getLootBoxManager().isLootBox(block)) {
            return true;
        }
        if (type == Material.OBSIDIAN && isArenaWall(block, arena)) {
            return true;
        }
        return false;
    }

    private boolean isArenaWall(Block block, Arena arena) {
        double cx = arena.getCenterX();
        double cz = arena.getCenterZ();
        double dist = CircleUtil.distanceFromCenter(cx, cz, block.getX(), block.getZ());
        int wallThickness = plugin.getPluginConfig().getWallThickness();
        return dist >= arena.getRadius() - 1 && dist <= arena.getRadius() + wallThickness + 1;
    }
}
