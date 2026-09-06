package com.hungergames.game;

import com.hungergames.HungerGamesPlugin;
import com.hungergames.arena.Arena;
import com.hungergames.arena.SettlementLayout;
import com.hungergames.arena.Settlements;
import com.hungergames.gamemode.GameMode;
import com.hungergames.gamemode.SettlementConfig;
import com.hungergames.util.ArenaEntityUtil;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Pillager;
import org.bukkit.entity.Villager;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Per-match settlement population: spawns village villagers at grace start,
 * refills building chests, then seeds and maintains the outpost garrison once
 * the opening forest phase ends. Mobs are tagged with
 * {@link ArenaEntityUtil#SETTLEMENT_MOB_KEY} so the end-of-match sweep removes them.
 */
public class SettlementManager {

    private static final Villager.Profession[] PROFESSIONS = {
            Villager.Profession.FARMER, Villager.Profession.LIBRARIAN,
            Villager.Profession.ARMORER, Villager.Profession.TOOLSMITH,
            Villager.Profession.WEAPONSMITH, Villager.Profession.BUTCHER,
    };

    private final HungerGamesPlugin plugin;
    private final Map<String, Long> lastRespawnByArena = new HashMap<>();
    private final Map<String, Boolean> garrisonSeededByArena = new HashMap<>();
    private final Random random = new Random();

    public SettlementManager(HungerGamesPlugin plugin) {
        this.plugin = plugin;
    }

    /** Called from beginGrace, after prepareMatchLoot. */
    public void onGraceStart(Game game) {
        GameMode mode = game.getGameMode();
        if (!Settlements.isActive(game.getArena(), mode)) {
            return;
        }
        SettlementConfig config = mode.getSettlement();
        World world = game.getArena().getWorld();
        if (world == null) {
            return;
        }
        lastRespawnByArena.remove(arenaKey(game.getArena()));
        garrisonSeededByArena.remove(arenaKey(game.getArena()));

        Settlements.refreshLayout(game.getArena(), mode);
        plugin.getLootBoxManager().fillBuildingChests(
                game.getArena(), resolveLootTier(mode, config));

        for (SettlementLayout.StructureSite site : game.getArena().getSettlementLayout().getMobSites()) {
            if (site.kind() == SettlementLayout.StructureSite.Kind.VILLAGE) {
                spawnVillagers(world, site, config.getVillagerCount());
            }
        }
    }

    /** Called once per second from tickActive; seeds then keeps outpost garrisons topped up. */
    public void tick(Game game) {
        GameMode mode = game.getGameMode();
        if (!Settlements.isActive(game.getArena(), mode)) {
            return;
        }
        if (!plugin.getTerrainPhaseManager().allowsHostileSpawning(game)) {
            return;
        }
        SettlementConfig config = mode.getSettlement();
        Arena arena = game.getArena();
        String key = arenaKey(arena);

        List<SettlementLayout.StructureSite> outposts = arena.getSettlementLayout().getMobSites().stream()
                .filter(site -> site.kind() == SettlementLayout.StructureSite.Kind.OUTPOST)
                .toList();

        World world = arena.getWorld();
        if (world == null || outposts.isEmpty()) {
            return;
        }

        if (!garrisonSeededByArena.getOrDefault(key, false)) {
            for (SettlementLayout.StructureSite site : outposts) {
                spawnGarrison(world, site, config.getGarrisonSize());
            }
            garrisonSeededByArena.put(key, true);
            lastRespawnByArena.put(key, System.currentTimeMillis());
            return;
        }
        int garrisonAlive = 0;
        double maxDistSq = (arena.getRadius() + 5.0) * (arena.getRadius() + 5.0);
        for (var entity : world.getEntitiesByClass(Pillager.class)) {
            Location loc = entity.getLocation();
            if ((loc.getX() - arena.getCenterX()) * (loc.getX() - arena.getCenterX())
                    + (loc.getZ() - arena.getCenterZ()) * (loc.getZ() - arena.getCenterZ()) <= maxDistSq
                    && ArenaEntityUtil.isSettlementMob(entity)) {
                garrisonAlive++;
            }
        }

        if (garrisonAlive >= config.getGarrisonSize() * outposts.size()) {
            return;
        }
        long now = System.currentTimeMillis();
        long last = lastRespawnByArena.getOrDefault(key, 0L);
        if (now - last < config.getGarrisonRespawnSeconds() * 1000L) {
            return;
        }

        SettlementLayout.StructureSite site = outposts.get(random.nextInt(outposts.size()));
        ensureChunkLoaded(world, site.x(), site.z());
        Location base = new Location(world, site.x() + 0.5, site.y(), site.z() + 0.5);
        Location spawnLoc = findSpawnableNear(base);
        if (spawnLoc == null) {
            return;
        }
        if (!plugin.getTerrainPhaseManager().isVolcanoForestAt(world, spawnLoc.getBlockX(), spawnLoc.getBlockZ())) {
            return;
        }
        spawnPillager(spawnLoc);
        lastRespawnByArena.put(key, now);
    }

    // ------------------------------------------------------------------

    private void spawnGarrison(World world, SettlementLayout.StructureSite site, int count) {
        ensureChunkLoaded(world, site.x(), site.z());
        Location base = new Location(world, site.x() + 0.5, site.y(), site.z() + 0.5);
        int spawned = 0;
        int attempts = 0;
        while (spawned < count && attempts < count * 8) {
            attempts++;
            Location candidate = base.clone().add(
                    random.nextInt(7) - 3,
                    0,
                    random.nextInt(7) - 3);
            Location resolved = resolveGround(candidate);
            if (resolved == null) {
                continue;
            }
            if (!plugin.getTerrainPhaseManager().isVolcanoForestAt(world, resolved.getBlockX(), resolved.getBlockZ())) {
                continue;
            }
            spawnPillager(resolved);
            spawned++;
        }
        if (spawned < count
                && plugin.getTerrainPhaseManager().isVolcanoForestAt(world, site.x(), site.z())) {
            spawnPillager(base); // fallback: tower interior
        }
    }

    private void spawnPillager(Location location) {
        Pillager pillager = location.getWorld().spawn(location, Pillager.class, entity -> {
            entity.setRemoveWhenFarAway(false);
            entity.setPatrolLeader(false);
        });
        ArenaEntityUtil.tagSettlementMob(plugin, pillager);
    }

    private void spawnVillagers(World world, SettlementLayout.StructureSite site, int count) {
        ensureChunkLoaded(world, site.x(), site.z());
        Location base = new Location(world, site.x() + 0.5, site.y(), site.z() + 0.5);
        int spawned = 0;
        int attempts = 0;
        while (spawned < count && attempts < count * 8) {
            attempts++;
            Location candidate = base.clone().add(
                    random.nextInt(13) - 6,
                    1,
                    random.nextInt(13) - 6);
            Location resolved = resolveGround(candidate);
            if (resolved == null) {
                continue;
            }
            spawnVillager(resolved);
            spawned++;
        }
    }

    private void spawnVillager(Location location) {
        Villager villager = location.getWorld().spawn(location, Villager.class, entity -> {
            entity.setProfession(PROFESSIONS[random.nextInt(PROFESSIONS.length)]);
            entity.setRemoveWhenFarAway(false);
            entity.setAdult();
        });
        ArenaEntityUtil.tagSettlementMob(plugin, villager);
    }

    /** Finds solid ground within a small column window; null when unsafe. */
    private Location resolveGround(Location start) {
        World world = start.getWorld();
        int x = start.getBlockX();
        int z = start.getBlockZ();
        for (int dy = -3; dy <= 4; dy++) {
            int y = start.getBlockY() + dy;
            Block below = world.getBlockAt(x, y - 1, z);
            Block feet = world.getBlockAt(x, y, z);
            Block head = world.getBlockAt(x, y + 1, z);
            if (!below.getType().isSolid()
                    || feet.getType() != Material.AIR
                    || head.getType() != Material.AIR) {
                continue;
            }
            return new Location(world, x + 0.5, y, z + 0.5);
        }
        return null;
    }

    private Location findSpawnableNear(Location base) {
        for (int attempt = 0; attempt < 12; attempt++) {
            Location candidate = base.clone().add(
                    random.nextInt(9) - 4, 0, random.nextInt(9) - 4);
            Location resolved = resolveGround(candidate);
            if (resolved != null) {
                return resolved;
            }
        }
        return null;
    }

    private void ensureChunkLoaded(World world, int x, int z) {
        Chunk chunk = world.getChunkAt(x >> 4, z >> 4);
        if (!chunk.isLoaded()) {
            chunk.load(true);
        }
    }

    private String resolveLootTier(GameMode mode, SettlementConfig config) {
        String fallback = config.getChestLootTierFallback();
        if (fallback != null && !fallback.isBlank()) {
            return fallback;
        }
        return mode.getLootTier();
    }

    private String arenaKey(Arena arena) {
        return arena.getWorldName() != null ? arena.getWorldName() : arena.getName();
    }
}
