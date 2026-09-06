package com.hungergames.listener;

import com.hungergames.HungerGamesPlugin;
import com.hungergames.arena.Arena;
import com.hungergames.game.Curse;
import com.hungergames.game.CurseManager;
import com.hungergames.game.Game;
import com.hungergames.game.GameState;
import com.hungergames.game.PlayerSession;
import com.hungergames.terrain.VolcanoPiglinHorde;
import com.hungergames.terrain.VolcanoUndergroundGuard;
import com.hungergames.util.ArenaBounds;
import com.hungergames.util.ArenaEntityUtil;
import com.hungergames.util.CircleUtil;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.PolarBear;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPickupArrowEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.event.vehicle.VehicleExitEvent;
import org.bukkit.inventory.ItemStack;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

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
        plugin.getCurseManager().onPlayerDeath(player);
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
        plugin.getGameManager().notifyRejoinOpportunity(event.getPlayer());
        plugin.getCurseManager().onPlayerJoin(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        plugin.getGameManager().handlePlayerQuit(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPvP(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        Player damager = extractPlayerDamager(event.getDamager());
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

    /**
     * Violence in or into a church condemns the aggressor: lifted into the air
     * under divine lightning and branded with a persistent curse. Covers melee
     * inside the nave and arrows (or other projectiles) shot at sanctuary from
     * outside — including through walls opened by volcano TNT.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChurchPvP(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        Player attacker = extractPlayerDamager(event.getDamager());
        if (attacker == null || victim.getUniqueId().equals(attacker.getUniqueId())) {
            return;
        }
        plugin.getGameManager().getPlayerGame(attacker).ifPresent(game -> {
            if (game.getState() != GameState.ACTIVE) {
                return;
            }
            Arena arena = game.getArena();
            if (arena == null) {
                return;
            }
            CurseManager curses = plugin.getCurseManager();
            if (curses.isInsideChurch(arena, attacker.getLocation())
                    || curses.isInsideChurch(arena, victim.getLocation())) {
                curses.condemn(game, attacker);
            }
        });
    }

    /** Curse damage adjustments: Glass Bones amplifies, Shaking Hands weakens. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onCursedDamage(EntityDamageByEntityEvent event) {
        double amount = event.getDamage();

        if (event.getEntity() instanceof Player victim) {
            Game game = plugin.getGameManager().getPlayerGame(victim).orElse(null);
            if (game != null && game.getState() == GameState.ACTIVE) {
                PlayerSession session = game.getSession(victim.getUniqueId());
                if (session != null && session.hasCurse(Curse.GLASS_BONES)) {
                    amount *= 1.25;
                }
            }
        }

        if (event.getDamager() instanceof Player attacker
                || (event.getDamager() instanceof Projectile projectile
                && projectile.getShooter() instanceof Player)) {
            Player source = extractPlayerDamager(event.getDamager());
            if (source != null) {
                Game game = plugin.getGameManager().getPlayerGame(source).orElse(null);
                if (game != null && game.getState() == GameState.ACTIVE) {
                    PlayerSession session = game.getSession(source.getUniqueId());
                    if (session != null && session.hasCurse(Curse.SHAKING_HANDS)) {
                        amount *= 0.70;
                    }
                }
            }
        }

        event.setDamage(amount);
    }

    // ------------------------------------------------------------------
    // Beast forms: the cursed player plays as an animal with claws/fangs
    // only - no building, no weapons, no items.
    // ------------------------------------------------------------------

    private boolean isBeast(Player player) {
        return plugin.getGameManager().getPlayerGame(player)
                .map(game -> {
                    PlayerSession session = game.getSession(player.getUniqueId());
                    return session != null && session.hasBeastForm();
                })
                .orElse(false);
    }

    /** Beasts cannot break blocks (paws lack fingers). */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBeastBlockBreak(BlockBreakEvent event) {
        if (isBeast(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    /** Beasts cannot place blocks. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBeastBlockPlace(BlockPlaceEvent event) {
        if (isBeast(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    /** Beasts cannot pick items off the ground. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBeastEntityPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && isBeast(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBeastPickupArrow(PlayerPickupArrowEvent event) {
        if (isBeast(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBeastInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player && isBeast(player)) {
            event.setCancelled(true);
            plugin.getCurseManager().stripBeastInventory(player);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBeastInventoryDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player && isBeast(player)) {
            event.setCancelled(true);
            plugin.getCurseManager().stripBeastInventory(player);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBeastInventoryOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player && isBeast(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBeastDropItem(PlayerDropItemEvent event) {
        if (isBeast(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBeastSwapHands(PlayerSwapHandItemsEvent event) {
        if (isBeast(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBeastShootBow(EntityShootBowEvent event) {
        if (event.getEntity() instanceof Player player && isBeast(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBeastConsume(PlayerItemConsumeEvent event) {
        if (isBeast(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    /** Beasts cannot wield weapons or tools. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBeastWeaponUse(PlayerItemHeldEvent event) {
        Player player = event.getPlayer();
        if (!isBeast(player)) {
            return;
        }
        ItemStack held = player.getInventory().getItem(event.getNewSlot());
        if (held != null && !held.getType().isAir()) {
            event.setCancelled(true);
            player.sendMessage("\u00A77Your paws cannot hold this.");
        }
        plugin.getCurseManager().stripBeastInventory(player);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBeastItemInteract(PlayerInteractEvent event) {
        if (!isBeast(event.getPlayer())) {
            return;
        }
        ItemStack item = event.getItem();
        if (item != null && !item.getType().isAir()) {
            event.setCancelled(true);
            plugin.getCurseManager().stripBeastInventory(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBeastInteractEntity(PlayerInteractEntityEvent event) {
        if (isBeast(event.getPlayer()) || (event.getRightClicked() instanceof LivingEntity living
                && ArenaEntityUtil.isBeastForm(living))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBeastDismount(VehicleExitEvent event) {
        if (event.getExited() instanceof Player player && isBeast(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBeastSneak(PlayerToggleSneakEvent event) {
        if (isBeast(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    /** Claw/fang swings while bound to the animal camera. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBeastSwing(PlayerAnimationEvent event) {
        if (event.getAnimationType() != PlayerAnimationType.ARM_SWING) {
            return;
        }
        if (!isBeast(event.getPlayer())) {
            return;
        }
        plugin.getCurseManager().tryBeastSwing(event.getPlayer());
    }

    /** Suppress vanilla punches; claw damage is applied from the animal body. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBeastAttack(EntityDamageByEntityEvent event) {
        Player attacker = extractPlayerDamager(event.getDamager());
        if (attacker == null || !isBeast(attacker)) {
            return;
        }
        if (plugin.getCurseManager().isBeastMeleeBypass(attacker)) {
            return;
        }
        if (event.getDamager() instanceof Player) {
            event.setCancelled(true);
        }
    }

    /** Cats always land on their feet. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBeastFall(EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.FALL
                || !(event.getEntity() instanceof Player player)) {
            return;
        }
        if (plugin.getGameManager().getPlayerGame(player).map(game -> {
            PlayerSession session = game.getSession(player.getUniqueId());
            return session != null && session.hasCurse(Curse.BEAST_CAT);
        }).orElse(false)) {
            event.setCancelled(true);
        }
    }

    /** Combat damage dealt to a beast's animal body transfers to the cursed player. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBeastBodyDamaged(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Mob mob) || !ArenaEntityUtil.isBeastForm(mob)) {
            return;
        }
        EntityDamageEvent.DamageCause cause = event.getCause();
        if (cause != EntityDamageEvent.DamageCause.ENTITY_ATTACK
                && cause != EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK
                && cause != EntityDamageEvent.DamageCause.PROJECTILE
                && cause != EntityDamageEvent.DamageCause.THORNS
                && cause != EntityDamageEvent.DamageCause.ENTITY_EXPLOSION
                && cause != EntityDamageEvent.DamageCause.MAGIC
                && cause != EntityDamageEvent.DamageCause.FIRE
                && cause != EntityDamageEvent.DamageCause.FIRE_TICK
                && cause != EntityDamageEvent.DamageCause.LAVA
                && cause != EntityDamageEvent.DamageCause.HOT_FLOOR
                && cause != EntityDamageEvent.DamageCause.DROWNING
                && cause != EntityDamageEvent.DamageCause.LIGHTNING) {
            return;
        }
        String ownerUuid = mob.getPersistentDataContainer()
                .get(ArenaEntityUtil.BEAST_FORM_KEY, org.bukkit.persistence.PersistentDataType.STRING);
        if (ownerUuid == null || ownerUuid.isEmpty()) {
            return;
        }
        Player owner = plugin.getServer().getPlayer(java.util.UUID.fromString(ownerUuid));
        if (owner == null || !owner.isOnline()) {
            return;
        }
        if (event instanceof EntityDamageByEntityEvent byEntity) {
            Player source = extractPlayerDamager(byEntity.getDamager());
            if (source != null && source.getUniqueId().equals(owner.getUniqueId())) {
                return;
            }
        }
        double transferred = Math.min(event.getFinalDamage(), owner.getHealth());
        owner.damage(Math.max(0.5, transferred));
        owner.getWorld().playSound(owner.getLocation(), Sound.ENTITY_CAT_HURT, 0.8f, 0.8f);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onVolcanoGuardianDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof LivingEntity living
                && ArenaEntityUtil.isVolcanoGuardian(living)) {
            event.setCancelled(true);
        }
    }

    /**
     * Attacking a volcano piglin starts a timed horde rage locked to that attacker.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPiglinAttacked(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Mob piglin)
                || !ArenaEntityUtil.isVolcanoPiglin(piglin)) {
            return;
        }
        Player attacker = extractPlayerDamager(event.getDamager());
        if (attacker == null) {
            return;
        }
        plugin.getGameManager().getPlayerGame(attacker).ifPresent(game ->
                plugin.getTerrainPhaseManager().getVolcanoEruptionPhase()
                        .map(phase -> phase.getPiglinHorde())
                        .filter(VolcanoPiglinHorde::isActive)
                        .ifPresent(horde -> horde.handlePiglinAttacked(attacker, piglin)));
    }

    /** Attacking a village villager summons the village's charged guardians. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVillagerAttacked(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Villager villager)
                || !ArenaEntityUtil.isSettlementMob(villager)) {
            return;
        }
        Player attacker = extractPlayerDamager(event.getDamager());
        if (attacker == null) {
            return;
        }
        plugin.getGameManager().getPlayerGame(attacker).ifPresent(game ->
                plugin.getRageManager().triggerGolemRage(attacker));
    }

    /** Attacking an ambient creeper triggers a charged-creeper bombardment. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCreeperAttacked(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Creeper creeper)
                || !ArenaEntityUtil.isVolcanoCreeper(creeper)) {
            return;
        }
        Player attacker = extractPlayerDamager(event.getDamager());
        if (attacker == null) {
            return;
        }
        plugin.getGameManager().getPlayerGame(attacker).ifPresent(game ->
                plugin.getRageManager().triggerCreeperRage(attacker));
    }

    /** When a hunted player dies, the horde calms down again. */
    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        if (event.getEntity() instanceof Mob mob && ArenaEntityUtil.isBeastForm(mob)) {
            event.getDrops().clear();
            event.setDroppedExp(0);
            return;
        }
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        plugin.getTerrainPhaseManager().getVolcanoEruptionPhase()
                .map(phase -> phase.getPiglinHorde())
                .filter(VolcanoPiglinHorde::isActive)
                .ifPresent(horde -> horde.handleTargetDeath(victim));
    }

    /**
     * Rage units stay locked on the player who started the hunt. Ambient nether
     * mobs never peel onto bystanders (hoglins/brutes would otherwise closest-player).
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPiglinTarget(EntityTargetLivingEntityEvent event) {
        if (!(event.getEntity() instanceof Mob mob) || !ArenaEntityUtil.isVolcanoPiglin(mob)) {
            return;
        }
        UUID ownerId = ArenaEntityUtil.getVolcanoPiglinHuntOwner(mob);
        LivingEntity proposed = event.getTarget();
        if (ownerId == null) {
            if (proposed instanceof Player) {
                event.setCancelled(true);
            }
            return;
        }
        if (proposed instanceof Player player && player.getUniqueId().equals(ownerId)) {
            return;
        }
        Player owner = plugin.getServer().getPlayer(ownerId);
        if (owner != null && owner.isOnline()) {
            event.setTarget(owner);
        } else {
            event.setCancelled(true);
        }
    }

    /**
     * Arena polar bears only hunt match players and never idle when a player is nearby.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onArcticBearTarget(EntityTargetLivingEntityEvent event) {
        if (!(event.getEntity() instanceof PolarBear bear) || !ArenaEntityUtil.isArcticMob(bear)) {
            return;
        }
        Game game = plugin.getGameManager().getActiveGame().orElse(null);
        if (game == null || (game.getState() != GameState.GRACE && game.getState() != GameState.ACTIVE)) {
            event.setCancelled(true);
            return;
        }
        LivingEntity proposed = event.getTarget();
        if (proposed instanceof Player player && game.getAlivePlayers().contains(player)) {
            return;
        }
        Player nearby = nearestAlivePlayer(game, bear.getLocation());
        if (nearby != null) {
            event.setTarget(nearby);
        } else {
            event.setCancelled(true);
        }
    }

    private Player nearestAlivePlayer(Game game, Location from) {
        Player best = null;
        double bestDist = Double.MAX_VALUE;
        for (Player player : game.getAlivePlayers()) {
            if (player == null || !player.isOnline() || player.isDead()) {
                continue;
            }
            if (from.getWorld() == null || player.getWorld() != from.getWorld()) {
                continue;
            }
            double dist = player.getLocation().distanceSquared(from);
            if (dist < bestDist) {
                bestDist = dist;
                best = player;
            }
        }
        return best;
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
        if (plugin.getPowerUpManager().isProtectedBlock(block)) {
            plugin.getGameManager().getPlayerGame(event.getPlayer()).ifPresent(game -> {
                if (isInArenaWorld(event.getPlayer(), game)) {
                    event.setCancelled(true);
                }
            });
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
                return;
            }
            // Keep the power-up beacon beams unobstructed.
            Block placed = event.getBlockPlaced();
            if (isAbovePowerUpBeacon(placed)) {
                event.setCancelled(true);
            }
        });
    }

    private boolean isAbovePowerUpBeacon(Block block) {
        for (int y = 1; y <= block.getWorld().getMaxHeight() - block.getY(); y++) {
            Block below = block.getRelative(0, -y, 0);
            if (below.getType() == Material.BEACON
                    && plugin.getPowerUpManager().isProtectedBlock(below)) {
                return true;
            }
        }
        return false;
    }

    private boolean isInArenaWorld(Player player, Game game) {
        return game.getArena().getWorld() != null
                && player.getWorld().equals(game.getArena().getWorld());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onLootBoxOpen(PlayerInteractEvent event) {
        if (isBeast(event.getPlayer())) {
            return;
        }
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
        if (isBeast(player)) {
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

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        Location location = event.getLocation();
        if (location.getWorld() == null || !isArenaWorld(location.getWorld().getName())) {
            return;
        }
        if (event.getEntity() instanceof PolarBear bear
                && !ArenaEntityUtil.isArcticMob(bear)
                && event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.CUSTOM) {
            event.setCancelled(true);
            return;
        }
        if (!ArenaEntityUtil.isVanillaHostileSpawn(event)) {
            return;
        }
        event.setCancelled(true);
    }

    private boolean isArenaWorld(String worldName) {
        for (Arena arena : plugin.getArenaManager().getArenas()) {
            if (worldName.equals(arena.getWorldName())) {
                return true;
            }
        }
        return false;
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
                        || block.getType() == Material.LAVA
                        || isExplosionProtected(block, arena));
                return;
            }
            event.blockList().clear();
        });
    }

    private boolean isExplosionProtected(Block block, Arena arena) {
        if (plugin.getPowerUpManager().isProtectedBlock(block)) {
            return true;
        }
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
