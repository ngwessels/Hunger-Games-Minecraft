package com.hungergames.game;

import com.hungergames.HungerGamesPlugin;
import com.hungergames.arena.Arena;
import com.hungergames.arena.SettlementLayout;
import com.hungergames.gamemode.GameMode;
import com.hungergames.gamemode.SettlementConfig;
import com.hungergames.util.ArenaEntityUtil;
import org.bukkit.Chunk;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Input;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Condemnation system for church violence: lifts the aggressor into the air
 * under divine lightning and brands them with a persistent custom curse that
 * survives every death for the remainder of the match. Applies both to PvP
 * inside the nave and to attacks on players taking sanctuary (arrows shot
 * through a TNT-opened wall count). Curses stack by church offense: tier 1
 * on the first, then 2, 3, and a primal animal form at 4. Earlier marks stay
 * in effect; further violence only repeats the lightning. Primal curses bind
 * the player into an animal: they see from its eyes, others see only the
 * animal, and paws cannot hold items.
 */
public class CurseManager {

    private static final int LIFT_TICKS = 25;
    private static final long STRIKE_COOLDOWN_MS = 2_000;
    private static final double CAMERA_SCALE = 0.12;
    private static final long BEAST_ATTACK_COOLDOWN_MS = 450;
    private static final double MIN_CURSED_MAX_HEALTH = 4.0;
    private static final UUID HEALTH_FLOOR_UUID =
            UUID.nameUUIDFromBytes("hg_curse_health_floor".getBytes());

    private final HungerGamesPlugin plugin;
    private final Map<UUID, Long> lastStrikeByPlayer = new HashMap<>();
    private final Map<UUID, BeastPossession> possessions = new HashMap<>();
    private final Map<UUID, Long> lastBeastAttackMs = new HashMap<>();
    private final Set<UUID> beastMeleeBypass = new HashSet<>();
    private final Random random = new Random();
    private BukkitTask syncTask;

    private static final class BeastPossession {
        private Mob body;
        private ArmorStand camera;
        private Curse curse;
    }

    public CurseManager(HungerGamesPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        if (syncTask != null) {
            return;
        }
        syncTask = new BukkitRunnable() {
            @Override
            public void run() {
                tickBeastForms();
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    public void shutdown() {
        if (syncTask != null) {
            syncTask.cancel();
            syncTask = null;
        }
        for (UUID playerId : Set.copyOf(possessions.keySet())) {
            Player player = plugin.getServer().getPlayer(playerId);
            releaseBeastForm(player);
        }
        possessions.clear();
    }

    /** True when the location is inside a generated church's interior bounds. */
    public boolean isInsideChurch(Arena arena, Location location) {
        for (SettlementLayout.ChurchSite church : arena.getSettlementLayout().getChurches()) {
            if (church.contains(location.getX(), location.getY(), location.getZ())) {
                return true;
            }
        }
        return false;
    }

    /** Condemns an attacker who shed blood in or into a church. */
    public void condemn(Game game, Player attacker) {
        GameMode mode = game.getGameMode();
        SettlementConfig config = mode != null ? mode.getSettlement() : null;
        if (config == null || !config.isCondemnationEnabled()) {
            return;
        }
        PlayerSession session = game.getSession(attacker.getUniqueId());
        if (session == null) {
            return;
        }

        long now = System.currentTimeMillis();
        Long last = lastStrikeByPlayer.get(attacker.getUniqueId());
        if (last != null && now - last < STRIKE_COOLDOWN_MS) {
            return;
        }
        lastStrikeByPlayer.put(attacker.getUniqueId(), now);

        playCondemnationSequence(attacker);

        int offense = session.getChurchOffenses();
        if (offense >= 4) {
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (attacker.isOnline()) {
                    attacker.sendTitle("\u00A75\u00A7k\u00A7lGOD SEES YOU\u00A7r", "", 10, 40, 10);
                }
            }, LIFT_TICKS);
            return;
        }

        offense = session.incrementChurchOffenses();
        Curse curse = Curse.randomForOffense(offense, random);
        boolean deepening = offense > 1;
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!attacker.isOnline()) {
                return;
            }
            applyStackedCurse(attacker, session, curse);
            String title = deepening
                    ? "\u00A75\u00A7k\u00A7lTHE CURSE STACKS\u00A7r"
                    : "\u00A75\u00A7k\u00A7lCONDEMNED\u00A7r";
            attacker.sendTitle(title,
                    "\u00A77Tier " + curse.getTierRoman() + " \u00A78\u2014 \u00A7d" + curse.getDisplayName(),
                    10, 60, 20);
            attacker.sendMessage("\u00A75" + curse.getLore());
            String verb = deepening
                    ? " spilled blood in the church again \u2014 a new curse stacks: \u00A7d"
                    : " spilled blood in the church \u2014 cursed with \u00A7d";
            broadcast(game, "\u00A7k\u00A7lCONDEMNED\u00A7r \u00A7e" + attacker.getName()
                    + verb + curse.getDisplayName() + "\u00A7e!");
        }, LIFT_TICKS);
    }

    private void applyStackedCurse(Player player, PlayerSession session, Curse next) {
        session.addCurse(next);
        next.apply(player);
        clampHealth(player);
        if (next.isBeastForm()) {
            summonBeastForm(player, next);
            player.sendMessage("\u00A77You may no longer build, place, or wield anything.");
        }
    }

    private void playCondemnationSequence(Player attacker) {
        Location loc = attacker.getLocation();
        attacker.getWorld().strikeLightningEffect(loc);
        attacker.getWorld().playSound(loc, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 2.0f, 0.6f);
        attacker.getWorld().playSound(loc, Sound.BLOCK_ANVIL_LAND, 1.5f, 0.5f);
        attacker.setGlowing(true);
        attacker.setVelocity(new Vector(0, 1.6, 0));
        attacker.addPotionEffect(new PotionEffect(PotionEffectType.LEVITATION, LIFT_TICKS, 0, false, false));

        new BukkitRunnable() {
            @Override
            public void run() {
                if (attacker.isOnline()) {
                    attacker.setFallDistance(0f);
                }
            }
        }.runTaskLater(plugin, LIFT_TICKS);
    }

    // ------------------------------------------------------------------
    // Beast forms: the cursed player looks from the animal's eyes while
    // others see only the animal. Movement is steered from player input.
    // ------------------------------------------------------------------

    public boolean isBeastCursed(PlayerSession session) {
        return session != null && session.hasBeastForm();
    }

    public Mob getBeastForm(Player player) {
        if (player == null) {
            return null;
        }
        BeastPossession possession = possessions.get(player.getUniqueId());
        return possession == null ? null : possession.body;
    }

    public boolean hasBeastForm(Player player) {
        Mob beast = getBeastForm(player);
        return beast != null && beast.isValid();
    }

    public void stripBeastInventory(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        PlayerInventory inventory = player.getInventory();
        inventory.clear();
        inventory.setArmorContents(new ItemStack[4]);
        inventory.setItemInOffHand(null);
        player.setCanPickupItems(false);
    }

    public boolean isBeastMeleeBypass(Player player) {
        return player != null && beastMeleeBypass.contains(player.getUniqueId());
    }

    /**
     * Claw/fang swing from the animal body. Returns true when an attack was attempted
     * (cooldown consumed); vanilla punches are suppressed separately.
     */
    public boolean tryBeastSwing(Player player) {
        if (player == null || !player.isOnline()) {
            return false;
        }
        BeastPossession possession = possessions.get(player.getUniqueId());
        if (possession == null || possession.body == null || !possession.body.isValid()
                || possession.curse == null) {
            return false;
        }
        if (!consumeAttackCooldown(player)) {
            return false;
        }
        Mob beast = possession.body;
        Curse curse = possession.curse;
        double range = curse.beastMeleeRange();
        Location eye = beast.getEyeLocation();
        Vector direction = eye.getDirection();
        RayTraceResult hit = beast.getWorld().rayTraceEntities(
                eye, direction, range, 0.4, entity -> entity instanceof LivingEntity living
                        && !living.equals(beast)
                        && !living.equals(player)
                        && !ArenaEntityUtil.isBeastCamera(entity)
                        && living.isValid());
        LivingEntity target = null;
        if (hit != null && hit.getHitEntity() instanceof LivingEntity living) {
            target = living;
        }
        if (target == null) {
            RayTraceResult blockHit = beast.getWorld().rayTraceBlocks(eye, direction, range,
                    FluidCollisionMode.NEVER, true);
            if (blockHit != null) {
                return true;
            }
        }
        if (target == null) {
            return true;
        }
        beastMeleeBypass.add(player.getUniqueId());
        try {
            target.damage(curse.beastClawDamage(), player);
        } finally {
            beastMeleeBypass.remove(player.getUniqueId());
        }
        return true;
    }

    public void onPlayerJoin(Player joining) {
        for (Map.Entry<UUID, BeastPossession> entry : possessions.entrySet()) {
            Player beastPlayer = plugin.getServer().getPlayer(entry.getKey());
            if (beastPlayer == null || !beastPlayer.isOnline() || beastPlayer.equals(joining)) {
                continue;
            }
            joining.hidePlayer(plugin, beastPlayer);
            if (entry.getValue().body != null && entry.getValue().body.isValid()) {
                joining.showEntity(plugin, entry.getValue().body);
            }
        }
    }

    public void onPlayerDeath(Player player) {
        removePossessionEntities(player);
    }

    public void releaseBeastForm(Player player) {
        removePossessionEntities(player);
        unbindBeastPlayer(player);
        if (player != null) {
            lastBeastAttackMs.remove(player.getUniqueId());
            beastMeleeBypass.remove(player.getUniqueId());
        }
    }

    private void summonBeastForm(Player player, Curse curse) {
        removePossessionEntities(player);
        Location loc = player.getLocation();
        ensureChunkLoaded(loc);
        bindBeastPlayer(player);

        Mob beast = (Mob) loc.getWorld().spawnEntity(loc, curse.beastType());
        ArenaEntityUtil.tagBeastForm(beast, player.getUniqueId());
        beast.setAI(false);
        beast.setAware(false);
        beast.setGravity(true);
        beast.setSilent(true);
        beast.setInvulnerable(false);
        beast.setPersistent(true);
        beast.setCanPickupItems(false);
        beast.setGlowing(true);
        beast.setCustomName(plugin.formatMessage("&5" + curse.getDisplayName() + " &7(" + player.getName() + ")"));
        beast.setCustomNameVisible(true);
        AttributeInstance beastHealth = beast.getAttribute(Attribute.MAX_HEALTH);
        if (beastHealth != null) {
            beastHealth.setBaseValue(Math.max(1.0, player.getMaxHealth()));
            beast.setHealth(Math.min(beastHealth.getValue(), Math.max(1.0, player.getHealth())));
        }
        beast.teleport(loc);
        player.hideEntity(plugin, beast);

        ArmorStand camera = loc.getWorld().spawn(beast.getEyeLocation(), ArmorStand.class, stand -> {
            stand.setInvisible(true);
            stand.setVisible(false);
            stand.setSmall(true);
            stand.setGravity(false);
            stand.setBasePlate(false);
            stand.setArms(false);
            stand.setCollidable(false);
            stand.setInvulnerable(true);
            stand.setPersistent(true);
            stand.setCanPickupItems(false);
            stand.setSilent(true);
            ArenaEntityUtil.tagBeastCamera(stand, player.getUniqueId());
        });

        BeastPossession possession = new BeastPossession();
        possession.body = beast;
        possession.camera = camera;
        possession.curse = curse;
        possessions.put(player.getUniqueId(), possession);

        camera.addPassenger(player);
        hidePlayerFromOthers(player);
    }

    private void tickBeastForms() {
        for (UUID playerId : Set.copyOf(possessions.keySet())) {
            Player player = plugin.getServer().getPlayer(playerId);
            BeastPossession possession = possessions.get(playerId);
            if (player == null || !player.isOnline() || possession == null) {
                if (player != null) {
                    releaseBeastForm(player);
                } else {
                    removePossessionEntities(playerId);
                }
                continue;
            }
            GameManager manager = plugin.getGameManager();
            if (manager == null) {
                continue;
            }
            Game game = manager.getPlayerGame(player).orElse(null);
            if (game == null
                    || (game.getState() != GameState.ACTIVE && game.getState() != GameState.GRACE)) {
                releaseBeastForm(player);
                continue;
            }
            if (player.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
                continue;
            }
            if (possession.body == null || !possession.body.isValid()) {
                removePossessionEntities(player);
                if (!player.isDead() && player.getHealth() > 0) {
                    player.setHealth(0);
                }
                continue;
            }
            if (possession.camera == null || !possession.camera.isValid()) {
                Curse curse = possession.curse;
                if (curse != null && curse.beastType() != null) {
                    summonBeastForm(player, curse);
                } else {
                    releaseBeastForm(player);
                }
                continue;
            }
            stripBeastInventory(player);
            enforceBeastBody(player);
            steerBeast(player, possession);
            snapCamera(player, possession);
        }
    }

    private void steerBeast(Player player, BeastPossession possession) {
        Mob beast = possession.body;
        Curse curse = possession.curse;
        Input input = player.getCurrentInput();
        Location look = player.getLocation();
        beast.setRotation(look.getYaw(), look.getPitch());

        Vector forward = look.getDirection();
        forward.setY(0);
        if (forward.lengthSquared() < 1.0e-6) {
            forward = new Vector(0, 0, 1);
        } else {
            forward.normalize();
        }
        Vector right = new Vector(-forward.getZ(), 0, forward.getX());
        Vector move = new Vector();
        if (input.isForward()) {
            move.add(forward);
        }
        if (input.isBackward()) {
            move.subtract(forward);
        }
        if (input.isRight()) {
            move.add(right);
        }
        if (input.isLeft()) {
            move.subtract(right);
        }

        double speed = input.isSprint() ? curse.beastWalkSpeed() * 1.4 : curse.beastWalkSpeed();
        double y = beast.getVelocity().getY();
        if (input.isJump() && isOnGround(beast)) {
            y = 0.42;
        }
        if (move.lengthSquared() > 1.0e-6) {
            move.normalize().multiply(speed);
        }
        move.setY(y);
        beast.setVelocity(move);
    }

    private void snapCamera(Player player, BeastPossession possession) {
        Location eye = possession.body.getEyeLocation();
        eye.setYaw(player.getLocation().getYaw());
        eye.setPitch(player.getLocation().getPitch());
        possession.camera.teleport(eye);
        if (!possession.camera.getPassengers().contains(player)) {
            possession.camera.addPassenger(player);
        }
        if (player.canSee(possession.body)) {
            player.hideEntity(plugin, possession.body);
        }
    }

    private void bindBeastPlayer(Player player) {
        stripBeastInventory(player);
        player.setInvisible(true);
        player.setCollidable(false);
        player.setSilent(true);
        player.setGlowing(false);
        AttributeInstance scale = player.getAttribute(Attribute.SCALE);
        if (scale != null) {
            scale.setBaseValue(CAMERA_SCALE);
        }
        hidePlayerFromOthers(player);
    }

    private void enforceBeastBody(Player player) {
        if (!player.isInvisible()) {
            player.setInvisible(true);
        }
        if (player.isCollidable()) {
            player.setCollidable(false);
        }
        player.setCanPickupItems(false);
        AttributeInstance scale = player.getAttribute(Attribute.SCALE);
        if (scale != null && Math.abs(scale.getBaseValue() - CAMERA_SCALE) > 0.01) {
            scale.setBaseValue(CAMERA_SCALE);
        }
        hidePlayerFromOthers(player);
    }

    private void unbindBeastPlayer(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        player.setInvisible(false);
        player.setCollidable(true);
        player.setSilent(false);
        player.setCanPickupItems(true);
        AttributeInstance scale = player.getAttribute(Attribute.SCALE);
        if (scale != null) {
            scale.setBaseValue(1.0);
        }
        for (Player other : plugin.getServer().getOnlinePlayers()) {
            if (!other.getUniqueId().equals(player.getUniqueId())) {
                other.showPlayer(plugin, player);
            }
        }
    }

    private void hidePlayerFromOthers(Player player) {
        for (Player other : plugin.getServer().getOnlinePlayers()) {
            if (!other.getUniqueId().equals(player.getUniqueId())) {
                other.hidePlayer(plugin, player);
            }
        }
    }

    private void removePossessionEntities(Player player) {
        if (player == null) {
            return;
        }
        removePossessionEntities(player.getUniqueId());
    }

    private void removePossessionEntities(UUID playerId) {
        BeastPossession possession = possessions.remove(playerId);
        if (possession == null) {
            return;
        }
        if (possession.camera != null && possession.camera.isValid()) {
            possession.camera.eject();
            possession.camera.remove();
        }
        if (possession.body != null && possession.body.isValid()) {
            possession.body.remove();
        }
    }

    private boolean consumeAttackCooldown(Player player) {
        long now = System.currentTimeMillis();
        Long last = lastBeastAttackMs.get(player.getUniqueId());
        if (last != null && now - last < BEAST_ATTACK_COOLDOWN_MS) {
            return false;
        }
        lastBeastAttackMs.put(player.getUniqueId(), now);
        return true;
    }

    private static boolean isOnGround(Mob beast) {
        if (beast.isOnGround()) {
            return true;
        }
        Location below = beast.getLocation().clone().subtract(0, 0.15, 0);
        return below.getBlock().getType().isSolid();
    }

    private void ensureChunkLoaded(Location loc) {
        Chunk chunk = loc.getWorld().getChunkAt(loc.getBlockX() >> 4, loc.getBlockZ() >> 4);
        if (!chunk.isLoaded()) {
            chunk.load(true);
        }
    }

    // ------------------------------------------------------------------

    /** Per-second upkeep of active curses during ACTIVE and GRACE. */
    public void tick(Game game) {
        if (game.getState() != GameState.ACTIVE && game.getState() != GameState.GRACE) {
            return;
        }
        for (PlayerSession session : game.getSessions().values()) {
            Player player = session.getPlayer();
            if (player == null || !player.isOnline() || !session.isAlive() || session.isDisconnected()) {
                continue;
            }
            for (Curse curse : session.getCurses()) {
                if (curse.hasPerSecondEffect()) {
                    curse.onSecond(player, game, runnable -> plugin.getServer().getScheduler().runTask(plugin, runnable));
                }
            }
        }
    }

    /** Re-applies stacked curses after death strips attribute modifiers. */
    public void reapplyOnRespawn(PlayerSession session) {
        Player player = session.getPlayer();
        if (player == null || !player.isOnline() || session.getCurses().isEmpty()) {
            return;
        }
        Curse beast = null;
        for (Curse curse : session.getCurses()) {
            curse.apply(player);
            if (curse.isBeastForm()) {
                beast = curse;
            }
        }
        clampHealth(player);
        if (beast != null) {
            summonBeastForm(player, beast);
        }
    }

    /** Removes all curse effects at match end, before sessions are wiped. */
    public void clear(Game game) {
        for (PlayerSession session : game.getSessions().values()) {
            clearPlayer(session);
        }
        lastStrikeByPlayer.clear();
        lastBeastAttackMs.clear();
        beastMeleeBypass.clear();
    }

    /** Undoes every stacked curse on this participant. Safe to call more than once. */
    public void clearPlayer(PlayerSession session) {
        if (session == null) {
            return;
        }
        Player player = session.getPlayer();
        releaseBeastForm(player);
        if (player != null) {
            lastStrikeByPlayer.remove(player.getUniqueId());
            lastBeastAttackMs.remove(player.getUniqueId());
            beastMeleeBypass.remove(player.getUniqueId());
        }
        if (player != null && player.isOnline()) {
            for (Curse curse : session.getCurses()) {
                curse.remove(player);
            }
            stripResidualEffects(player, session.isSpectator());
        }
        session.clearCurses();
    }

    private void stripResidualEffects(Player player, boolean keepInvisible) {
        Curse.removeCurseModifiers(player.getAttribute(Attribute.MAX_HEALTH));
        Curse.removeCurseModifiers(player.getAttribute(Attribute.MOVEMENT_SPEED));
        Curse.removeCurseModifiers(player.getAttribute(Attribute.SCALE));
        Curse.removeModifier(player.getAttribute(Attribute.MAX_HEALTH), HEALTH_FLOOR_UUID);
        AttributeInstance scale = player.getAttribute(Attribute.SCALE);
        if (scale != null) {
            scale.setBaseValue(1.0);
        }
        if (!keepInvisible) {
            player.setInvisible(false);
        }
        player.setCollidable(true);
        player.setSilent(false);
        player.setCanPickupItems(true);
        player.setGlowing(false);
        for (Player other : plugin.getServer().getOnlinePlayers()) {
            if (!other.getUniqueId().equals(player.getUniqueId())) {
                other.showPlayer(plugin, player);
            }
        }
        double max = player.getMaxHealth();
        if (player.getHealth() > max) {
            player.setHealth(max);
        }
    }

    private static void clampHealth(Player player) {
        AttributeInstance health = player.getAttribute(Attribute.MAX_HEALTH);
        if (health != null) {
            Curse.removeModifier(health, HEALTH_FLOOR_UUID);
            double value = health.getValue();
            if (value < MIN_CURSED_MAX_HEALTH) {
                health.addModifier(new AttributeModifier(HEALTH_FLOOR_UUID, "hg_curse_health_floor",
                        MIN_CURSED_MAX_HEALTH - value, AttributeModifier.Operation.ADD_NUMBER));
            }
        }
        double max = player.getMaxHealth();
        if (player.getHealth() > max) {
            player.setHealth(max);
        }
    }

    private void broadcast(Game game, String message) {
        String formatted = plugin.formatMessage(message);
        for (Player recipient : game.getNotificationRecipients()) {
            recipient.sendMessage(formatted);
        }
    }
}
