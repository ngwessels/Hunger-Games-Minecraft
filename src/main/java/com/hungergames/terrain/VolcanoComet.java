package com.hungergames.terrain;

import com.hungergames.HungerGamesPlugin;
import com.hungergames.arena.Arena;
import com.hungergames.game.Game;
import com.hungergames.game.GameState;
import com.hungergames.game.MatchPacing;
import com.hungergames.game.PlayerSession;
import com.hungergames.gamemode.CometConfig;
import com.hungergames.util.ArenaEntityUtil;
import com.hungergames.util.ColorUtil;
import com.hungergames.util.FastBlockMutator;
import com.hungergames.util.ParticleUtil;
import org.bukkit.Chunk;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/**
 * A glowing comet visible in the volcano sky for the whole match. It closes in
 * with match time, then dives and strips the arena at the finale.
 */
public class VolcanoComet {

    private static final Material[] CHUNK_MATERIALS = {
            Material.MAGMA_BLOCK, Material.GLOWSTONE, Material.CRYING_OBSIDIAN,
            Material.ORANGE_CONCRETE, Material.MAGMA_BLOCK, Material.GLOWSTONE
    };
    private static final float[][] CHUNK_OFFSETS = {
            {0f, 0f, 0f},
            {0.7f, 0.35f, -0.2f},
            {-0.55f, 0.15f, 0.5f},
            {0.25f, -0.4f, 0.65f},
            {-0.4f, -0.25f, -0.55f},
            {0.15f, 0.55f, 0.3f}
    };
    private static final float[] CHUNK_SCALES = {1.0f, 0.45f, 0.4f, 0.38f, 0.35f, 0.3f};
    private static final BlockData AIR = Material.AIR.createBlockData();
    private static final BlockData LAVA = Material.LAVA.createBlockData();
    private static final BlockData MAGMA = Material.MAGMA_BLOCK.createBlockData();
    private static final BlockData NETHERRACK = Material.NETHERRACK.createBlockData();
    private static final BlockData FIRE = Material.FIRE.createBlockData();
    private static final int PRELOAD_SAMPLE_LIMIT = 400;
    private static final int PRELOAD_CHUNK_LIMIT = 12;

    private enum Phase {
        APPROACH, DIVE, STRIKE, OBLITERATE
    }

    private final HungerGamesPlugin plugin;
    private final CometGravityAnomalies gravity = new CometGravityAnomalies();
    private final FastBlockMutator mutator = new FastBlockMutator();
    private final List<BlockDisplay> pieces = new ArrayList<>();
    private BukkitTask task;
    private Game game;
    private Arena arena;
    private CometConfig config;
    private Phase phase = Phase.APPROACH;
    private Location start;
    private Location impact;
    private float currentScale;
    private double spin;
    private Chunk forcedChunk;
    private boolean warnedFalling;
    private int diveTick;
    private int strikeTick;
    private int shockRadius;
    private int shockIndex;
    private int soundTick;
    private double diveFromProgress;
    private Runnable onFinished;
    private BitSet clearedColumns;
    private int columnOriginX;
    private int columnOriginZ;
    private int columnStride;

    public VolcanoComet(HungerGamesPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean isActive() {
        return task != null && game != null;
    }

    public boolean isImpacting(Game forGame) {
        return isActive() && forGame == game
                && (phase == Phase.DIVE || phase == Phase.STRIKE || phase == Phase.OBLITERATE);
    }

    public void start(Game game) {
        destroy();
        CometConfig comet = game.getGameMode().getComet();
        if (comet == null || !comet.isEnabled()) {
            return;
        }
        Arena arena = game.getArena();
        World world = arena.getWorld();
        if (world == null) {
            return;
        }
        this.game = game;
        this.arena = arena;
        this.config = comet;
        this.phase = Phase.APPROACH;
        this.spin = 0;
        this.warnedFalling = false;
        this.diveTick = 0;
        this.strikeTick = 0;
        this.shockRadius = 0;
        this.shockIndex = 0;
        this.soundTick = 0;
        this.onFinished = null;
        this.clearedColumns = null;
        this.currentScale = comet.getStartScale();
        gravity.reset();

        double cx = arena.getCenterX();
        double cz = arena.getCenterZ();
        this.start = new Location(world, cx + comet.getStartOffsetX(), comet.getStartHeight(),
                cz + comet.getStartOffsetZ());
        this.impact = new Location(world, cx, comet.getImpactHeight(), cz);
        spawnPieces(world, start.clone());
        forceChunk(start);

        this.task = new BukkitRunnable() {
            @Override
            public void run() {
                tick();
            }
        }.runTaskTimer(plugin, 1L, 1L);

        if (game.getState() != GameState.ENDING) {
            for (Player player : game.getNotificationRecipients()) {
                player.sendMessage(plugin.formatMessage(
                        "&6A comet burns in the sky. It is getting closer."));
                player.playSound(player.getLocation(), Sound.ENTITY_WITHER_AMBIENT, 0.7f, 0.4f);
            }
        }
    }

    /**
     * Starts the terminal dive. Returns false when this game has no comet.
     */
    public boolean tryImpact(Game ending, Runnable finished) {
        if (ending.getGameMode().getComet() == null || !ending.getGameMode().getComet().isEnabled()) {
            return false;
        }
        if (game != ending || !isActive()) {
            start(ending);
        }
        if (game != ending) {
            return false;
        }
        if (phase == Phase.APPROACH) {
            diveFromProgress = approachProgress();
            phase = Phase.DIVE;
            diveTick = 0;
            onFinished = finished;
            for (Player player : ending.getNotificationRecipients()) {
                player.sendTitle(
                        ColorUtil.color("&c&lTHE COMET FALLS"),
                        ColorUtil.color("&6Look up — the arena will not survive."),
                        5, 50, 10);
                player.playSound(player.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 1.4f, 0.5f);
            }
            spectateForFinale(ending);
        } else if (onFinished == null) {
            onFinished = finished;
        }
        return true;
    }

    public void clearPlayerGravity(Player player) {
        gravity.clearPlayer(player);
    }

    public void destroy() {
        gravity.clear(game);
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (BlockDisplay piece : pieces) {
            if (piece != null && piece.isValid()) {
                piece.remove();
            }
        }
        pieces.clear();
        if (forcedChunk != null && forcedChunk.isLoaded()) {
            forcedChunk.setForceLoaded(false);
        }
        forcedChunk = null;
        game = null;
        arena = null;
        config = null;
        phase = Phase.APPROACH;
        onFinished = null;
        clearedColumns = null;
        mutator.endTick();
    }

    private void tick() {
        if (game == null || arena == null || config == null) {
            destroy();
            return;
        }
        World world = arena.getWorld();
        if (world == null) {
            destroy();
            return;
        }
        GameState state = game.getState();
        if (state != GameState.GRACE && state != GameState.ACTIVE && state != GameState.ENDING) {
            destroy();
            return;
        }

        double progress = visualProgress();
        currentScale = lerp(config.getStartScale(), config.getEndScale(), progress);
        Location here = lerp(start, impact, progress);
        spin += 0.08 + progress * 0.18;
        updatePieces(here);
        forceChunk(here);
        spawnTrail(world, here, progress);
        playApproachSound(world, here, progress);
        maybeWarnFalling(progress);
        if (phase == Phase.APPROACH || phase == Phase.DIVE) {
            gravity.tick(plugin, game, arena, config, progress);
        }

        if (phase == Phase.DIVE) {
            diveTick++;
            if (diveTick >= config.getDiveTicks()) {
                phase = Phase.STRIKE;
                strikeTick = 0;
                gravity.clear(game);
                strike(world, impact);
            }
        } else if (phase == Phase.STRIKE) {
            strikeTick++;
            strikeEffects(world, impact, strikeTick);
            if (strikeTick >= 12) {
                phase = Phase.OBLITERATE;
                shockRadius = 0;
                shockIndex = 0;
                prepareColumnTracker();
            }
        } else if (phase == Phase.OBLITERATE) {
            if (tickObliterate(world)) {
                Runnable callback = onFinished;
                destroy();
                if (callback != null) {
                    callback.run();
                }
            }
        }
    }

    private double visualProgress() {
        if (phase == Phase.APPROACH) {
            return Math.min(0.92, approachProgress());
        }
        if (phase == Phase.DIVE) {
            double t = diveTick / (double) Math.max(1, config.getDiveTicks());
            t = t * t * (3.0 - 2.0 * t);
            return diveFromProgress + (1.0 - diveFromProgress) * t;
        }
        return 1.0;
    }

    private double approachProgress() {
        double raw = MatchPacing.getMatchProgress(game, plugin.getPluginConfig());
        if (raw < 0) {
            raw = Math.min(0.9, game.getMatchElapsedSeconds() / 1200.0);
        }
        return Math.pow(Math.max(0.0, Math.min(1.0, raw)), 1.35);
    }

    private void spawnPieces(World world, Location origin) {
        for (int i = 0; i < CHUNK_MATERIALS.length; i++) {
            int index = i;
            BlockDisplay display = world.spawn(origin, BlockDisplay.class, entity -> {
                entity.setBlock(CHUNK_MATERIALS[index].createBlockData());
                entity.setViewRange(config.getViewRange());
                entity.setShadowRadius(0f);
                entity.setShadowStrength(0f);
                entity.setBrightness(new Display.Brightness(15, 15));
                entity.setBillboard(Display.Billboard.FIXED);
                entity.setGlowing(true);
                entity.setGlowColorOverride(Color.fromRGB(255, 120, 20));
                entity.setTeleportDuration(3);
                entity.setInterpolationDuration(3);
                entity.setPersistent(false);
                ArenaEntityUtil.tagComet(plugin, entity);
            });
            pieces.add(display);
        }
        applyTransforms(origin);
    }

    private void updatePieces(Location center) {
        for (BlockDisplay piece : pieces) {
            if (piece == null || !piece.isValid()) {
                continue;
            }
            piece.teleport(center);
        }
        applyTransforms(center);
    }

    private void applyTransforms(Location center) {
        Quaternionf spinQuat = new Quaternionf().rotateY((float) spin).rotateZ((float) (spin * 0.35));
        for (int i = 0; i < pieces.size(); i++) {
            BlockDisplay piece = pieces.get(i);
            if (piece == null || !piece.isValid()) {
                continue;
            }
            float pieceScale = currentScale * CHUNK_SCALES[i];
            Vector3f local = new Vector3f(
                    CHUNK_OFFSETS[i][0] * currentScale,
                    CHUNK_OFFSETS[i][1] * currentScale,
                    CHUNK_OFFSETS[i][2] * currentScale);
            spinQuat.transform(local);
            Vector3f translation = new Vector3f(local).sub(pieceScale / 2f, pieceScale / 2f, pieceScale / 2f);
            piece.setTransformation(new Transformation(
                    translation,
                    spinQuat,
                    new Vector3f(pieceScale, pieceScale, pieceScale),
                    new Quaternionf()));
        }
    }

    private void spawnTrail(World world, Location head, double progress) {
        double dx = start.getX() - impact.getX();
        double dy = start.getY() - impact.getY();
        double dz = start.getZ() - impact.getZ();
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 0.001) {
            return;
        }
        dx /= len;
        dy /= len;
        dz /= len;
        int sparks = 6 + (int) (progress * 14);
        for (int i = 1; i <= sparks; i++) {
            double back = i * (1.4 + currentScale * 0.25);
            double x = head.getX() + dx * back;
            double y = head.getY() + dy * back;
            double z = head.getZ() + dz * back;
            ParticleUtil.spawnForced(world, Particle.FLAME, x, y, z, 3, 0.35, 0.35, 0.35, 0.01);
            if (i % 2 == 0) {
                ParticleUtil.spawnForced(world, Particle.LAVA, x, y, z, 1, 0.2, 0.2, 0.2, 0.0);
            }
            if (i % 3 == 0) {
                ParticleUtil.spawnForced(world, Particle.CAMPFIRE_COSY_SMOKE, x, y, z, 1, 0.25, 0.25, 0.25, 0.0);
            }
        }
        ParticleUtil.spawnForced(world, Particle.FLAME, head.getX(), head.getY(), head.getZ(),
                8 + (int) (progress * 12), currentScale * 0.2, currentScale * 0.2, currentScale * 0.2, 0.02);
    }

    private void playApproachSound(World world, Location here, double progress) {
        if (phase != Phase.APPROACH && phase != Phase.DIVE) {
            return;
        }
        soundTick++;
        int interval = phase == Phase.DIVE ? 8 : 35;
        if (soundTick % interval != 0) {
            return;
        }
        float volume = 0.4f + (float) progress * 1.6f;
        world.playSound(here, Sound.BLOCK_FIRE_AMBIENT, volume, 0.5f + (float) progress * 0.4f);
        if (progress > 0.55) {
            world.playSound(here, Sound.ENTITY_BLAZE_SHOOT, volume * 0.5f, 0.4f);
        }
    }

    private void maybeWarnFalling(double progress) {
        if (warnedFalling || progress < 0.72 || phase != Phase.APPROACH) {
            return;
        }
        warnedFalling = true;
        for (Player player : game.getNotificationRecipients()) {
            player.sendMessage(plugin.formatMessage("&cThe comet is falling toward the arena!"));
            player.playSound(player.getLocation(), Sound.ENTITY_WITHER_SPAWN, 0.8f, 0.6f);
        }
    }

    private void spectateForFinale(Game ending) {
        for (PlayerSession session : ending.getSessions().values()) {
            Player player = session.getPlayer();
            if (player == null || !player.isOnline()) {
                continue;
            }
            session.setSpectator(true);
            player.setGameMode(org.bukkit.GameMode.SPECTATOR);
            if (player.getWorld() == impact.getWorld()) {
                Location look = player.getLocation();
                look.setDirection(impact.toVector().subtract(look.toVector()));
                player.teleport(look);
            }
        }
    }

    private void strike(World world, Location at) {
        world.playSound(at, Sound.ENTITY_GENERIC_EXPLODE, 6f, 0.4f);
        world.playSound(at, Sound.ENTITY_WITHER_DEATH, 4f, 0.5f);
        world.playSound(at, Sound.ENTITY_ENDER_DRAGON_DEATH, 3f, 0.7f);
        world.strikeLightningEffect(at);
        ParticleUtil.spawnForced(world, Particle.EXPLOSION, at.getX(), at.getY(), at.getZ(),
                8, 3, 2, 3, 0.1);
        ParticleUtil.spawnForced(world, Particle.FLASH, at.getX(), at.getY(), at.getZ(),
                4, 1, 1, 1, 0.0);
        for (Player player : game.getNotificationRecipients()) {
            player.sendTitle(
                    ColorUtil.color("&4&lIMPACT"),
                    ColorUtil.color("&6The arena is obliterated."),
                    2, 40, 15);
        }
    }

    private void strikeEffects(World world, Location at, int tick) {
        ParticleUtil.spawnForced(world, Particle.FLAME, at.getX(), at.getY(), at.getZ(),
                40, 6, 4, 6, 0.15);
        ParticleUtil.spawnForced(world, Particle.LAVA, at.getX(), at.getY(), at.getZ(),
                20, 5, 2, 5, 0.0);
        if (tick % 3 == 0) {
            world.playSound(at, Sound.ENTITY_GENERIC_EXPLODE, 3f, 0.5f + tick * 0.04f);
            double angle = tick * 0.7;
            Location bolt = at.clone().add(Math.cos(angle) * 18, 8, Math.sin(angle) * 18);
            world.strikeLightningEffect(bolt);
        }
    }

    private boolean tickObliterate(World world) {
        int maxR = arena.getRadius() + 2;
        int budget = config.getObliterateColumnsPerTick();
        int used = 0;
        double cx = arena.getCenterX();
        double cz = arena.getCenterZ();
        mutator.beginTick(world);
        try {
            while (used < budget && shockRadius <= maxR) {
                int points = ringPointCount(shockRadius);
                while (used < budget && shockIndex < points) {
                    int x = ringX(cx, shockRadius, shockIndex, points);
                    int z = ringZ(cz, shockRadius, shockIndex, points);
                    blastColumn(world, x, z, shockRadius, maxR);
                    shockIndex++;
                    used++;
                }
                if (shockIndex >= points) {
                    if (shockRadius % 12 == 0) {
                        Location ring = new Location(world, cx, config.getImpactHeight(), cz);
                        world.playSound(ring, Sound.ENTITY_GENERIC_EXPLODE, 3.5f, 0.35f);
                        ParticleUtil.spawnForced(world, Particle.EXPLOSION, cx, config.getImpactHeight(), cz,
                                6, shockRadius * 0.15, 2, shockRadius * 0.15, 0.05);
                    }
                    shockRadius++;
                    shockIndex = 0;
                }
            }
            if (shockRadius <= maxR) {
                preloadUpcomingChunks(world, cx, cz, maxR);
            }
            return shockRadius > maxR;
        } finally {
            mutator.endTick();
        }
    }

    private void blastColumn(World world, int x, int z, int dist, int maxR) {
        Chunk chunk = mutator.chunkAt(x, z);
        if (chunk == null) {
            return;
        }
        double norm = maxR <= 0 ? 1.0 : Math.min(1.0, dist / (double) maxR);
        int floor = 1 + (int) Math.round(7 * norm);
        if (markColumn(x, z)) {
            int highest = Math.min(world.getMaxHeight() - 2, mutator.highestBlockY(x, z) + 8);
            int top = Math.max(floor, highest);
            for (int y = floor; y <= top; y++) {
                if (mutator.isSkippable(chunk, x, y, z)) {
                    continue;
                }
                mutator.set(chunk, x, y, z, AIR);
            }
        }
        BlockData bed = norm < 0.22 ? LAVA : (norm < 0.55 ? MAGMA : NETHERRACK);
        mutator.set(chunk, x, Math.max(1, floor - 1), z, bed);
        if (norm < 0.35 && (x + z) % 5 == 0 && mutator.isAir(chunk, x, floor, z)) {
            mutator.set(chunk, x, floor, z, FIRE);
        }
    }

    private void prepareColumnTracker() {
        int maxR = arena.getRadius() + 2;
        columnOriginX = (int) Math.floor(arena.getCenterX()) - maxR - 2;
        columnOriginZ = (int) Math.floor(arena.getCenterZ()) - maxR - 2;
        columnStride = maxR * 2 + 8;
        clearedColumns = new BitSet(columnStride * columnStride);
    }

    /**
     * @return true when this column has not been stripped yet (the expensive Y
     *         scan should run). Floor/fire are still applied on repeats so a
     *         later ring sample can overwrite the crust exactly as before.
     */
    private boolean markColumn(int x, int z) {
        if (clearedColumns == null || columnStride <= 0) {
            return true;
        }
        int ox = x - columnOriginX;
        int oz = z - columnOriginZ;
        if (ox < 0 || oz < 0 || ox >= columnStride || oz >= columnStride) {
            return true;
        }
        int index = ox * columnStride + oz;
        if (clearedColumns.get(index)) {
            return false;
        }
        clearedColumns.set(index);
        return true;
    }

    private void preloadUpcomingChunks(World world, double cx, double cz, int maxR) {
        int r = shockRadius;
        int idx = shockIndex;
        int inspected = 0;
        int loaded = 0;
        int lastCx = Integer.MIN_VALUE;
        int lastCz = Integer.MIN_VALUE;
        while (inspected < PRELOAD_SAMPLE_LIMIT && loaded < PRELOAD_CHUNK_LIMIT && r <= maxR) {
            int points = ringPointCount(r);
            if (idx >= points) {
                r++;
                idx = 0;
                continue;
            }
            int x = ringX(cx, r, idx, points);
            int z = ringZ(cz, r, idx, points);
            int chunkX = x >> 4;
            int chunkZ = z >> 4;
            if (chunkX != lastCx || chunkZ != lastCz) {
                lastCx = chunkX;
                lastCz = chunkZ;
                if (!world.isChunkLoaded(chunkX, chunkZ)) {
                    world.getChunkAt(chunkX, chunkZ);
                    loaded++;
                }
            }
            idx++;
            inspected++;
        }
    }

    private static int ringPointCount(int radius) {
        return Math.max(8, (int) Math.round(2 * Math.PI * Math.max(1, radius)));
    }

    private static int ringX(double cx, int radius, int index, int points) {
        return (int) Math.round(cx + Math.cos((2 * Math.PI * index) / points) * radius);
    }

    private static int ringZ(double cz, int radius, int index, int points) {
        return (int) Math.round(cz + Math.sin((2 * Math.PI * index) / points) * radius);
    }

    private void forceChunk(Location location) {
        Chunk chunk = location.getChunk();
        if (!chunk.isLoaded()) {
            chunk.load(true);
        }
        if (forcedChunk != null && forcedChunk != chunk) {
            forcedChunk.setForceLoaded(false);
        }
        chunk.setForceLoaded(true);
        forcedChunk = chunk;
    }

    private static float lerp(float a, float b, double t) {
        return (float) (a + (b - a) * t);
    }

    private static Location lerp(Location a, Location b, double t) {
        return new Location(a.getWorld(),
                a.getX() + (b.getX() - a.getX()) * t,
                a.getY() + (b.getY() - a.getY()) * t,
                a.getZ() + (b.getZ() - a.getZ()) * t);
    }
}
