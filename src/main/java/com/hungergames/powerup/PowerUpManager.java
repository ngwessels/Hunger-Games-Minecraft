package com.hungergames.powerup;

import com.hungergames.HungerGamesPlugin;
import com.hungergames.arena.Arena;
import com.hungergames.arena.ArenaGenerator;
import com.hungergames.game.Game;
import com.hungergames.game.GameState;
import com.hungergames.gamemode.TerrainPhaseConfig;
import com.hungergames.terrain.phases.VolcanoEruptionPhase;
import com.hungergames.util.ArenaEntityUtil;
import com.hungergames.util.ArenaScatterUtil;
import com.hungergames.util.CircleUtil;
import com.hungergames.util.ColorUtil;
import com.hungergames.util.ParticleUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

public class PowerUpManager {

    private final HungerGamesPlugin plugin;
    private final Random random = new Random();
    private final Map<String, GamePowerUpState> stateByArena = new HashMap<>();
    private PlayerGuardianSession activeGuardianPilot;

    public PowerUpManager(HungerGamesPlugin plugin) {
        this.plugin = plugin;
    }

    public void tick(Game game) {
        if (!plugin.getPluginConfig().isPowerUpsEnabled()) {
            return;
        }
        if (game.getState() != GameState.ACTIVE) {
            return;
        }

        GamePowerUpState state = stateByArena.computeIfAbsent(
                arenaKey(game), key -> {
                    GamePowerUpState created = new GamePowerUpState();
                    resetSpawnTimer(created);
                    return created;
                });

        tickInstances(game, state);
        tickActionBars(game, state);

        state.secondsUntilNextSpawn--;
        if (state.secondsUntilNextSpawn > 0) {
            return;
        }
        resetSpawnTimer(state);
        trySpawnWave(game, state);
    }

    public void clear(Game game) {
        GamePowerUpState state = stateByArena.remove(arenaKey(game));
        if (state != null) {
            for (PowerUpInstance instance : new ArrayList<>(state.instances)) {
                destroyInstance(game, instance, DestroyReason.CLEANUP, false);
            }
        }
        if (activeGuardianPilot != null && activeGuardianPilot.getPlayer() != null
                && game.isParticipant(activeGuardianPilot.getPlayer().getUniqueId())) {
            activeGuardianPilot.end(false);
            activeGuardianPilot = null;
        }
    }

    public boolean isPlayerPilotActive() {
        return activeGuardianPilot != null && activeGuardianPilot.isActive();
    }

    public boolean hasActiveUltimateMarker(Game game) {
        GamePowerUpState state = stateByArena.get(arenaKey(game));
        if (state == null) {
            return false;
        }
        return state.instances.stream().anyMatch(i -> "volcano_ascendant".equalsIgnoreCase(i.getDefinitionId()));
    }

    public void clearGuardianPilot(PlayerGuardianSession session) {
        if (activeGuardianPilot == session) {
            activeGuardianPilot = null;
        }
    }

    public Optional<PlayerGuardianSession> getActiveGuardianPilot() {
        return Optional.ofNullable(activeGuardianPilot);
    }

    public boolean forceSpawn(Game game, String definitionId) {
        PowerUpDefinition def = plugin.getPowerUpRegistry().rollById(definitionId).orElse(null);
        if (def == null) {
            return false;
        }
        GamePowerUpState state = stateByArena.computeIfAbsent(arenaKey(game), key -> new GamePowerUpState());
        Location location = resolveSpawnLocation(game, def, state);
        if (location == null) {
            return false;
        }
        spawnInstance(game, state, def, location);
        return true;
    }

    public void destroyByMarker(ArmorStand stand) {
        if (!ArenaEntityUtil.isPowerUpMarker(stand)) {
            return;
        }
        UUID entityId = stand.getUniqueId();
        for (Map.Entry<String, GamePowerUpState> entry : stateByArena.entrySet()) {
            Game game = plugin.getGameManager().getGame(entry.getKey()).orElse(null);
            if (game == null) {
                continue;
            }
            for (PowerUpInstance instance : entry.getValue().instances) {
                if (entityId.equals(instance.getMarkerEntityId())) {
                    destroyInstance(game, instance, DestroyReason.TORNADO, true);
                    return;
                }
            }
        }
    }

    private void trySpawnWave(Game game, GamePowerUpState state) {
        int alive = game.getAlivePlayers().size();
        if (alive <= 0) {
            return;
        }

        int maxActive = computeMaxActive(alive);
        if (state.instances.size() >= maxActive) {
            return;
        }

        int spawnCount = Math.min(
                computeSpawnCount(alive),
                maxActive - state.instances.size());

        List<Location> existing = state.instances.stream()
                .map(PowerUpInstance::getSpawnLocation)
                .toList();
        List<Location> locations = ArenaScatterUtil.findRandomLocations(
                plugin, game.getArena(), spawnCount, existing, null);

        int spawned = 0;
        for (Location location : locations) {
            Optional<PowerUpDefinition> def = plugin.getPowerUpRegistry().rollNormal();
            if (def.isEmpty()) {
                break;
            }
            spawnInstance(game, state, def.get(), location);
            spawned++;
        }

        trySpawnUltimate(game, state);

        if (spawned == 0 && locations.isEmpty()) {
            // still reset timer even if nothing spawned
        }
    }

    private void trySpawnUltimate(Game game, GamePowerUpState state) {
        if (isPlayerPilotActive() || hasActiveUltimateMarker(game)) {
            return;
        }
        double chance = plugin.getPluginConfig().getPowerUpUltimateSpawnChance();
        if (random.nextDouble() > chance) {
            return;
        }

        Optional<VolcanoEruptionPhase> volcanoPhase = plugin.getTerrainPhaseManager().getVolcanoEruptionPhase();
        if (volcanoPhase.isEmpty() || !volcanoPhase.get().isVolcanoGuardianActive()) {
            return;
        }

        Optional<PowerUpDefinition> ultimate = plugin.getPowerUpRegistry().roll(PowerUpSpawnRule.GUARDIAN_ACTIVE);
        if (ultimate.isEmpty()) {
            return;
        }

        Location location = volcanoPhase.get().rollUltimatePowerUpLocation(ultimate.get());
        if (location == null) {
            return;
        }
        spawnInstance(game, state, ultimate.get(), location);
    }

    private Location resolveSpawnLocation(Game game, PowerUpDefinition def, GamePowerUpState state) {
        if (def.getSpawnRule() == PowerUpSpawnRule.GUARDIAN_ACTIVE) {
            Optional<VolcanoEruptionPhase> volcanoPhase = plugin.getTerrainPhaseManager().getVolcanoEruptionPhase();
            if (volcanoPhase.isEmpty() || !volcanoPhase.get().isVolcanoGuardianActive()) {
                return null;
            }
            return volcanoPhase.get().rollUltimatePowerUpLocation(def);
        }
        List<Location> existing = state.instances.stream()
                .map(PowerUpInstance::getSpawnLocation)
                .toList();
        List<Location> locations = ArenaScatterUtil.findRandomLocations(
                plugin, game.getArena(), 1, existing, null);
        return locations.isEmpty() ? null : locations.getFirst();
    }

    private void spawnInstance(Game game, GamePowerUpState state, PowerUpDefinition def, Location groundLocation) {
        World world = groundLocation.getWorld();
        if (world == null) {
            return;
        }

        ArenaGenerator generator = plugin.getArenaGenerator();
        int surfaceY = generator.findSurfaceAt(world, groundLocation.getBlockX(), groundLocation.getBlockZ(),
                (int) game.getArena().getCenterY());
        int dropHeight = plugin.getPluginConfig().getPowerUpDropHeightBlocks();
        Location spawnLoc = groundLocation.clone();
        spawnLoc.setY(surfaceY + dropHeight);

        PowerUpInstance instance = new PowerUpInstance(def.getId(), groundLocation);
        ArmorStand stand = world.spawn(spawnLoc, ArmorStand.class, entity -> configureMarker(entity, def));
        instance.setMarkerEntityId(stand.getUniqueId());
        state.instances.add(instance);

        announceSpawn(game, def);
    }

    private void configureMarker(ArmorStand stand, PowerUpDefinition def) {
        stand.setVisible(false);
        stand.setGravity(true);
        stand.setGlowing(true);
        stand.setInvulnerable(true);
        stand.setMarker(false);
        stand.setCollidable(true);
        stand.setCustomNameVisible(true);
        stand.setCustomName(ColorUtil.color(def.getDisplayName()));

        ItemStack helmet = new ItemStack(def.getMaterial());
        ItemMeta meta = helmet.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ColorUtil.color(def.getDisplayName()));
            helmet.setItemMeta(meta);
        }
        stand.getEquipment().setHelmet(helmet);
        stand.getPersistentDataContainer().set(ArenaEntityUtil.POWER_UP_KEY, PersistentDataType.STRING, def.getId());
    }

    private void announceSpawn(Game game, PowerUpDefinition def) {
        String name = ColorUtil.stripColor(ColorUtil.color(def.getDisplayName()));
        for (Player player : game.getNotificationRecipients()) {
            player.sendMessage(plugin.formatMessage("&d&lPower-up incoming: &f" + name));
            player.sendTitle(ColorUtil.color(def.getDisplayName()), ColorUtil.color("&7Dropping from the sky!"), 10, 50, 10);
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
        }
    }

    private void tickInstances(Game game, GamePowerUpState state) {
        Arena arena = game.getArena();
        World world = arena.getWorld();
        if (world == null) {
            return;
        }

        Iterator<PowerUpInstance> iterator = state.instances.iterator();
        while (iterator.hasNext()) {
            PowerUpInstance instance = iterator.next();
            ArmorStand stand = resolveMarker(instance);
            if (stand == null || stand.isDead()) {
                iterator.remove();
                continue;
            }

            Optional<PowerUpDefinition> defOpt = plugin.getPowerUpRegistry().getDefinition(instance.getDefinitionId());
            if (defOpt.isEmpty()) {
                stand.remove();
                iterator.remove();
                continue;
            }
            PowerUpDefinition def = defOpt.get();

            if (instance.getState() == PowerUpState.FALLING) {
                tickFalling(game, arena, state, instance, stand, def);
                if (!state.instances.contains(instance)) {
                    continue;
                }
            } else {
                tickLanded(game, instance, stand, def);
                if (!state.instances.contains(instance)) {
                    continue;
                }
                if (instance.getDespawnAtMillis() > 0
                        && System.currentTimeMillis() >= instance.getDespawnAtMillis()) {
                    destroyInstance(game, instance, DestroyReason.DESPAWN, true);
                }
            }

            spawnParticles(instance, stand, def);
        }
    }

    private void tickFalling(Game game, Arena arena, GamePowerUpState state, PowerUpInstance instance, ArmorStand stand, PowerUpDefinition def) {
        World world = arena.getWorld();
        Location loc = stand.getLocation();
        if (!isInsideArena(arena, loc)) {
            stand.remove();
            state.instances.remove(instance);
            return;
        }

        if (loc.getY() < world.getMinHeight() + 5) {
            stand.remove();
            state.instances.remove(instance);
            return;
        }

        if (stand.isOnGround() || isRestingOnBlock(stand)) {
            landInstance(game, instance, stand);
        }
    }

    private void tickLanded(Game game, PowerUpInstance instance, ArmorStand stand, PowerUpDefinition def) {
        tryPickup(game, instance, stand, def);
    }

    private void landInstance(Game game, PowerUpInstance instance, ArmorStand stand) {
        stand.setGravity(false);
        stand.setMarker(true);
        stand.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
        instance.setState(PowerUpState.LANDED);
        instance.setLandedAtMillis(System.currentTimeMillis());
        int despawnSeconds = plugin.getPluginConfig().getPowerUpDespawnSeconds();
        instance.setDespawnAtMillis(System.currentTimeMillis() + despawnSeconds * 1000L);
        instance.setSpawnLocation(stand.getLocation());

        World world = stand.getWorld();
        if (world != null) {
            world.playSound(stand.getLocation(), Sound.ENTITY_GENERIC_SPLASH, 1f, 1.2f);
        }
    }

    private boolean isRestingOnBlock(ArmorStand stand) {
        Location below = stand.getLocation().clone().subtract(0, 0.2, 0);
        Material type = below.getBlock().getType();
        return type.isSolid() && Math.abs(stand.getVelocity().getY()) < 0.1;
    }

    private void tryPickup(Game game, PowerUpInstance instance, ArmorStand stand, PowerUpDefinition def) {
        if (instance.getState() != PowerUpState.LANDED) {
            return;
        }

        double pickupRadius = plugin.getPluginConfig().getPowerUpPickupRadius();
        Location loc = stand.getLocation();
        for (Player player : game.getAlivePlayers()) {
            if (player.getLocation().distance(loc) > pickupRadius) {
                continue;
            }
            if (!applyPickup(game, player, instance, def)) {
                return;
            }
            destroyInstance(game, instance, DestroyReason.PICKUP, true);
            String name = ColorUtil.stripColor(ColorUtil.color(def.getDisplayName()));
            broadcast(game, "&a" + player.getName() + " &7claimed &f" + name + "&7!");
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.5f);
            return;
        }
    }

    private boolean applyPickup(Game game, Player player, PowerUpInstance instance, PowerUpDefinition def) {
        if (def.getEffectType() == PowerUpEffectType.GUARDIAN_CONTROL) {
            if (isPlayerPilotActive()) {
                return false;
            }
            Optional<VolcanoEruptionPhase> volcanoPhase = plugin.getTerrainPhaseManager().getVolcanoEruptionPhase();
            if (volcanoPhase.isEmpty()) {
                return false;
            }
            TerrainPhaseConfig config = plugin.getTerrainPhaseManager().getCurrentPhaseConfig();
            if (config == null) {
                return false;
            }
            PlayerGuardianSession session = new PlayerGuardianSession(
                    plugin, game, player, def, volcanoPhase.get(), config);
            if (!session.start()) {
                return false;
            }
            activeGuardianPilot = session;
            broadcast(game, "&4&l" + player.getName() + " &4has become the Volcano Guardian!");
            return true;
        }
        return plugin.getPowerUpRegistry().apply(player, def);
    }

    public void destroyInstance(Game game, PowerUpInstance instance, DestroyReason reason, boolean feedback) {
        GamePowerUpState state = stateByArena.get(arenaKey(game));
        if (state == null) {
            return;
        }

        ArmorStand stand = resolveMarker(instance);
        if (stand != null && !stand.isDead()) {
            if (feedback && reason == DestroyReason.TORNADO) {
                plugin.getPowerUpRegistry().getDefinition(instance.getDefinitionId()).ifPresent(def -> {
                    String name = ColorUtil.stripColor(ColorUtil.color(def.getDisplayName()));
                    broadcast(game, "&c&lFire tornado &7destroyed &f" + name + "&7!");
                    World world = stand.getWorld();
                    if (world != null) {
                        world.playSound(stand.getLocation(), Sound.ENTITY_ITEM_BREAK, 1f, 0.8f);
                        ParticleUtil.spawn(world, Particle.LARGE_SMOKE, stand.getLocation(), 12, 0.3, 0.3, 0.3, 0.02);
                    }
                });
            } else if (feedback && reason == DestroyReason.DESPAWN) {
                plugin.getPowerUpRegistry().getDefinition(instance.getDefinitionId()).ifPresent(def -> {
                    String name = ColorUtil.stripColor(ColorUtil.color(def.getDisplayName()));
                    broadcast(game, "&7Power-up &f" + name + " &7expired unclaimed.");
                });
            }
            stand.remove();
        }
        state.instances.remove(instance);
    }

    private void tickActionBars(Game game, GamePowerUpState state) {
        if (state.instances.isEmpty()) {
            return;
        }
        state.actionBarTick++;
        if (state.actionBarTick % 20 != 0) {
            return;
        }

        int activeCount = state.instances.size();
        for (Player recipient : game.getNotificationRecipients()) {
            PowerUpInstance nearest = findNearestInstance(recipient, state);
            if (nearest == null) {
                continue;
            }
            ArmorStand stand = resolveMarker(nearest);
            if (stand == null) {
                continue;
            }
            plugin.getPowerUpRegistry().getDefinition(nearest.getDefinitionId()).ifPresent(def -> {
                String direction = formatDirection(recipient.getLocation(), stand.getLocation());
                int distance = (int) recipient.getLocation().distance(stand.getLocation());
                String prefix = activeCount > 1 ? "&e(" + activeCount + " active) " : "";
                String bar = prefix + "&6" + ColorUtil.stripColor(ColorUtil.color(def.getDisplayName()))
                        + " &7| &f" + distance + "m &7" + direction;
                recipient.spigot().sendMessage(
                        net.md_5.bungee.api.ChatMessageType.ACTION_BAR,
                        net.md_5.bungee.api.chat.TextComponent.fromLegacyText(ColorUtil.color(bar)));
            });
        }
    }

    private PowerUpInstance findNearestInstance(Player player, GamePowerUpState state) {
        PowerUpInstance nearest = null;
        double nearestDist = Double.MAX_VALUE;
        for (PowerUpInstance instance : state.instances) {
            ArmorStand stand = resolveMarker(instance);
            if (stand == null) {
                continue;
            }
            double dist = player.getLocation().distanceSquared(stand.getLocation());
            if (dist < nearestDist) {
                nearestDist = dist;
                nearest = instance;
            }
        }
        return nearest;
    }

    private String formatDirection(Location from, Location to) {
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        double angle = Math.toDegrees(Math.atan2(-dx, dz));
        if (angle < 0) {
            angle += 360;
        }
        if (angle >= 337.5 || angle < 22.5) {
            return "N";
        }
        if (angle < 67.5) {
            return "NE";
        }
        if (angle < 112.5) {
            return "E";
        }
        if (angle < 157.5) {
            return "SE";
        }
        if (angle < 202.5) {
            return "S";
        }
        if (angle < 247.5) {
            return "SW";
        }
        if (angle < 292.5) {
            return "W";
        }
        return "NW";
    }

    private void spawnParticles(PowerUpInstance instance, ArmorStand stand, PowerUpDefinition def) {
        World world = stand.getWorld();
        if (world == null) {
            return;
        }
        PowerUpState state = instance.getState();
        Particle particle = def.getParticle();
        int count = state == PowerUpState.FALLING ? 6 : 12;
        double spread = state == PowerUpState.FALLING ? 0.25 : 0.5;
        ParticleUtil.spawnForced(world, particle, stand.getLocation().getX(), stand.getLocation().getY(),
                stand.getLocation().getZ(), count, spread, spread, spread, 0.01);
        spawnBeaconBeam(instance, stand, def);
    }

    private void spawnBeaconBeam(PowerUpInstance instance, ArmorStand stand, PowerUpDefinition def) {
        int interval = plugin.getPluginConfig().getPowerUpBeaconBeamIntervalTicks();
        if (instance.incrementBeaconTick() % interval != 0) {
            return;
        }

        Location base = instance.getState() == PowerUpState.FALLING
                ? instance.getSpawnLocation()
                : stand.getLocation();
        World world = base.getWorld();
        if (world == null) {
            return;
        }

        double beamX = base.getX() + 0.5;
        double beamZ = base.getZ() + 0.5;
        double startY = base.getY() + 0.2;
        int height = plugin.getPluginConfig().getPowerUpBeaconBeamHeightBlocks();
        double maxY = Math.min(startY + height, world.getMaxHeight() - 1);

        Particle accent = def.getParticle();
        for (double y = startY; y < maxY; y += 2.0) {
            ParticleUtil.spawnForced(world, Particle.END_ROD, beamX, y, beamZ, 1, 0, 0, 0, 0);
            if (((int) y) % 6 == 0) {
                ParticleUtil.spawnForced(world, accent, beamX, y, beamZ, 2, 0.08, 0.08, 0.08, 0.01);
            }
        }

        ParticleUtil.spawnForced(world, accent, beamX, startY, beamZ, 16, 0.6, 0.15, 0.6, 0.02);
        ParticleUtil.spawnForced(world, Particle.END_ROD, beamX, startY + 1.0, beamZ, 4, 0.2, 0.05, 0.2, 0.01);

        if (instance.getState() == PowerUpState.FALLING) {
            Location drop = stand.getLocation();
            ParticleUtil.spawnForced(world, Particle.END_ROD,
                    drop.getX(), drop.getY(), drop.getZ(), 3, 0.15, 0.15, 0.15, 0.01);
        }
    }

    private ArmorStand resolveMarker(PowerUpInstance instance) {
        UUID id = instance.getMarkerEntityId();
        if (id == null) {
            return null;
        }
        Entity entity = Bukkit.getEntity(id);
        if (entity instanceof ArmorStand stand) {
            return stand;
        }
        return null;
    }

    private boolean isInsideArena(Arena arena, Location loc) {
        return CircleUtil.isInsideCircle(
                arena.getCenterX(), arena.getCenterZ(),
                loc.getX(), loc.getZ(),
                arena.getRadius());
    }

    private int computeSpawnCount(int alive) {
        int perSpawn = plugin.getPluginConfig().getPowerUpPlayersPerSpawn();
        int min = plugin.getPluginConfig().getPowerUpMinSpawnsPerWave();
        int max = plugin.getPluginConfig().getPowerUpMaxSpawnsPerWave();
        return Math.min(max, Math.max(min, (alive + perSpawn - 1) / perSpawn));
    }

    private int computeMaxActive(int alive) {
        int perActive = plugin.getPluginConfig().getPowerUpPlayersPerActive();
        int cap = plugin.getPluginConfig().getPowerUpMaxActiveCap();
        return Math.min(cap, Math.max(1, (alive + perActive - 1) / perActive));
    }

    private void resetSpawnTimer(GamePowerUpState state) {
        int min = plugin.getPluginConfig().getPowerUpMinIntervalSeconds();
        int max = plugin.getPluginConfig().getPowerUpMaxIntervalSeconds();
        if (max <= min) {
            state.secondsUntilNextSpawn = min;
        } else {
            state.secondsUntilNextSpawn = min + random.nextInt(max - min + 1);
        }
    }

    private void broadcast(Game game, String message) {
        String formatted = plugin.formatMessage(message);
        for (Player player : game.getNotificationRecipients()) {
            player.sendMessage(formatted);
        }
    }

    private String arenaKey(Game game) {
        return game.getArena().getName().toLowerCase();
    }

    private static final class GamePowerUpState {
        private final List<PowerUpInstance> instances = new ArrayList<>();
        private int secondsUntilNextSpawn;
        private int actionBarTick;
    }
}
