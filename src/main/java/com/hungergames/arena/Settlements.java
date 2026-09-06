package com.hungergames.arena;

import com.hungergames.gamemode.GameMode;
import com.hungergames.gamemode.SettlementConfig;
import com.hungergames.util.BlockBatchUtil;
import com.hungergames.util.CircleUtil;
import com.hungergames.util.SpawnLocationUtil;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Door;
import org.bukkit.block.data.type.Ladder;
import org.bukkit.block.data.type.Stairs;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Deterministic settlement buildings for large volcano arenas: a pillager
 * outpost tower, a small village, and scattered ruins. Appended after the
 * grove pass so its block changes override terrain; chest and mob spawn
 * locations are recorded on the arena layout for per-match population.
 */
public final class Settlements {

    private static final long SITE_SALT = 0x517E77L;
    private static final long BUILD_SALT = 0x3A11B0CL;

    private Settlements() {
    }

    public static boolean isActive(Arena arena, GameMode gameMode) {
        if (arena == null || gameMode == null
                || !"volcano".equalsIgnoreCase(gameMode.getInitialTerrain())) {
            return false;
        }
        SettlementConfig config = gameMode.getSettlement();
        return config != null && config.isEnabled()
                && gameMode.getForestRadius() >= config.getMinRadius();
    }

    /**
     * Recomputes chest/mob/church sites from the terrain seed without placing
     * blocks. Needed after a world-folder restore or plugin restart.
     */
    public static void refreshLayout(Arena arena, GameMode gameMode) {
        arena.getSettlementLayout().clear();
        if (!isActive(arena, gameMode)) {
            return;
        }
        int baseY = (int) arena.getCenterY();
        int radius = arena.getRadius();
        int forest = gameMode.getForestRadius();
        int blend = gameMode.getArctic() != null && gameMode.getArctic().isEnabled()
                ? gameMode.getArctic().getBlendWidth() : 0;
        double cx = arena.getCenterX();
        double cz = arena.getCenterZ();
        int minX = (int) (cx - radius);
        int maxX = (int) (cx + radius);
        int minZ = (int) (cz - radius);
        int maxZ = (int) (cz + radius);
        int[][] heightmap = TerrainHeightmap.generate(cx, cz, radius, minX, maxX, minZ, maxZ,
                baseY, arena.getTerrainSeed(), gameMode.getInitialTerrain(), forest, blend);
        addBuildings(new ArrayList<>(), arena, gameMode, heightmap, minX, minZ,
                arena.getSettlementLayout());
    }

    private record Sites(List<double[]> outposts, List<double[]> villages,
                         List<double[]> ruins) {
    }

    /** Recomputes deterministic sites; shared by builder and exclusion checks. */
    public static Sites planSites(Arena arena, GameMode gameMode) {
        SettlementConfig config = gameMode.getSettlement();
        double cx = arena.getCenterX();
        double cz = arena.getCenterZ();
        int radius = gameMode.getForestRadius();

        Random random = new Random(arena.getTerrainSeed() * 31L + SITE_SALT);

        int outpostCount = Math.max(1, config.getOutpostsPer100Radius() * Math.max(1, radius / 100));
        int villageCount = Math.max(1, config.getVillagesPer100Radius() * Math.max(1, radius / 100));
        int ruinCount = config.getRuinsBaseCount()
                + config.getRuinsPer100Radius() * Math.max(1, radius / 100);

        double baseBearing = random.nextDouble() * Math.PI * 2;

        List<double[]> outposts = new ArrayList<>();
        for (int i = 0; i < outpostCount; i++) {
            double bearing = baseBearing + i * (Math.PI * 2 / outpostCount);
            double[] site = polarSite(arena, gameMode,
                    cx + Math.cos(bearing) * radius * 0.55,
                    cz + Math.sin(bearing) * radius * 0.55, 8);
            if (site != null) {
                outposts.add(site);
            }
        }

        List<double[]> villages = new ArrayList<>();
        for (int i = 0; i < villageCount; i++) {
            // Halfway between outpost bearings so multi-outpost rings never
            // drop a village on top of an outpost (they share the ring at 2+).
            double bearing = baseBearing + Math.PI / outpostCount
                    + i * (Math.PI * 2 / villageCount);
            List<double[]> placed = findVillageSite(arena, gameMode, bearing, outposts);
            villages.addAll(placed);
        }

        List<double[]> ruins = new ArrayList<>();
        for (int i = 0; i < ruinCount; i++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double dist = radius * (0.25 + random.nextDouble() * 0.45);
            double[] site = polarSite(arena, gameMode,
                    cx + Math.cos(angle) * dist,
                    cz + Math.sin(angle) * dist, 6);
            if (site != null && !conflicts(site, outposts, villages)) {
                ruins.add(site);
            }
        }

        return new Sites(outposts, villages, ruins);
    }

    /**
     * Scans bearings outward from the preferred one for the first spot that
     * passes site validation and keeps 45+ blocks from every outpost. The
     * deterministic scan order guarantees identical placement per seed.
     */
    private static List<double[]> findVillageSite(Arena arena, GameMode gameMode, double preferredBearing,
                                                  List<double[]> outposts) {
        int radius = gameMode.getForestRadius();
        double cx = arena.getCenterX();
        double cz = arena.getCenterZ();
        // ~6 degree steps around the full ring; preferred bearing first.
        List<Double> bearings = new ArrayList<>();
        bearings.add(preferredBearing);
        for (int step = 1; step <= 30; step++) {
            double offset = step * (Math.PI / 30);
            bearings.add(preferredBearing + offset);
            bearings.add(preferredBearing - offset);
        }
        List<double[]> placed = new ArrayList<>();
        for (double bearing : bearings) {
            double[] site = polarSite(arena, gameMode,
                    cx + Math.cos(bearing) * radius * 0.50,
                    cz + Math.sin(bearing) * radius * 0.50, 14);
            if (site != null && !conflicts(site, outposts)) {
                placed.add(site);
                return placed;
            }
        }
        return placed;
    }

    public static void addBuildings(List<BlockBatchUtil.BlockChange> changes, Arena arena,
                                    GameMode gameMode, int[][] heightmap, int minX, int minZ,
                                    SettlementLayout layout) {
        if (!isActive(arena, gameMode)) {
            return;
        }
        Random random = new Random(arena.getTerrainSeed() * 961748941L + BUILD_SALT);
        Sites sites = planSites(arena, gameMode);
        layout.clear();

        for (double[] site : sites.outposts()) {
            buildOutpost(changes, heightmap, minX, minZ, site[0], site[1], layout);
        }
        for (double[] site : sites.villages()) {
            buildVillage(changes, heightmap, minX, minZ, site[0], site[1], layout, random);
        }
        for (double[] site : sites.ruins()) {
            buildRuins(changes, heightmap, minX, minZ,
                    (int) Math.round(site[0]), (int) Math.round(site[1]),
                    4 + random.nextInt(4), layout, random);
        }
    }

    /** True when a column is close enough to a settlement to skip tree placement. */
    public static boolean isNearAnySite(double[] sitesFlat, double x, double z) {
        if (sitesFlat == null) {
            return false;
        }
        for (int i = 0; i + 1 < sitesFlat.length; i += 2) {
            double dx = x - sitesFlat[i];
            double dz = z - sitesFlat[i + 1];
            if (dx * dx + dz * dz <= 26 * 26) {
                return true;
            }
        }
        return false;
    }

    /** Flattened {x0, z0, x1, z1, ...} of all valid settlement sites. */
    public static double[] siteCoordinates(Sites sites) {
        List<double[]> all = new ArrayList<>();
        all.addAll(sites.outposts());
        all.addAll(sites.villages());
        all.addAll(sites.ruins());
        double[] flat = new double[all.size() * 2];
        for (int i = 0; i < all.size(); i++) {
            flat[i * 2] = all.get(i)[0];
            flat[i * 2 + 1] = all.get(i)[1];
        }
        return flat;
    }

    /** Validates a candidate center; returns {x, z} or null when unusable. */
    private static double[] polarSite(Arena arena, GameMode gameMode, double px, double pz, int padRadius) {
        double cx = arena.getCenterX();
        double cz = arena.getCenterZ();
        int radius = gameMode.getForestRadius();
        int x = (int) Math.round(px);
        int z = (int) Math.round(pz);

        if (!CircleUtil.isInsideCircle(cx, cz, x, z, radius - padRadius - 2)
                || VolcanoCaldera.isInsideCalderaArea(cx, cz, x, z)) {
            return null;
        }
        if (SpawnLocationUtil.isOnSpawnRing(x, z, cx, cz, radius)) {
            return null;
        }
        return new double[]{x, z};
    }

    private static boolean conflicts(double[] site, List<double[]>... groups) {
        for (List<double[]> group : groups) {
            for (double[] other : group) {
                double dx = site[0] - other[0];
                double dz = site[1] - other[1];
                if (dx * dx + dz * dz < 45 * 45) {
                    return true;
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Outpost: dark-oak frame tower with fenced lookout, ladder, chests.
    // ------------------------------------------------------------------

    private static void buildOutpost(List<BlockBatchUtil.BlockChange> changes,
                                     int[][] heightmap, int minX, int minZ,
                                     double sx, double sz, SettlementLayout layout) {
        int x = (int) sx;
        int z = (int) sz;
        int baseY = TerrainHeightmap.getHeight(heightmap, minX, minZ, x, z);
        flattenPad(changes, heightmap, minX, minZ, x, z, 7, baseY);
        int topY = baseY + 12;

        int[][] posts = {{-2, -2}, {-2, 2}, {2, -2}, {2, 2}};
        for (int y = baseY + 1; y <= topY; y++) {
            for (int[] post : posts) {
                changes.add(new BlockBatchUtil.BlockChange(x + post[0], y, z + post[1],
                        Material.DARK_OAK_LOG));
            }
        }
        // Cross-brace each story so the frame reads as solid.
        for (int level : new int[]{5, 9}) {
            buildFloorSlab(changes, x, baseY + level, z, 2, Material.DARK_OAK_PLANKS);
        }

        buildLookoutDeck(changes, x, topY, z);

        for (int y = baseY + 1; y <= topY + 1; y++) {
            changes.add(new BlockBatchUtil.BlockChange(x + 2, y, z, Material.LADDER,
                    ladderFacing(BlockFace.WEST)));
        }
        changes.add(new BlockBatchUtil.BlockChange(x, topY + 1, z + 1, Material.CHEST));
        layout.addChest(x, topY + 1, z + 1);
        changes.add(new BlockBatchUtil.BlockChange(x - 1, baseY + 1, z + 1, Material.CHEST));
        layout.addChest(x - 1, baseY + 1, z + 1);

        buildCagePosts(changes, x + 5, z, baseY);
        layout.addMobSite(x, baseY + 1, z, SettlementLayout.StructureSite.Kind.OUTPOST);
    }

    private static void buildFloorSlab(List<BlockBatchUtil.BlockChange> changes,
                                       int cx, int y, int cz, int halfExtent, Material material) {
        for (int dx = -halfExtent + 1; dx <= halfExtent - 1; dx++) {
            for (int dz = -halfExtent + 1; dz <= halfExtent - 1; dz++) {
                changes.add(new BlockBatchUtil.BlockChange(cx + dx, y, cz + dz, material));
            }
        }
    }

    private static void buildLookoutDeck(List<BlockBatchUtil.BlockChange> changes,
                                         int cx, int y, int cz) {
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                changes.add(new BlockBatchUtil.BlockChange(cx + dx, y, cz + dz,
                        Material.DARK_OAK_PLANKS));
            }
        }
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                boolean rim = Math.abs(dx) == 3 || Math.abs(dz) == 3;
                boolean corner = Math.abs(dx) == 3 && Math.abs(dz) == 3;
                if (!rim || corner) {
                    continue;
                }
                changes.add(new BlockBatchUtil.BlockChange(cx + dx, y + 1, cz + dz,
                        Material.OAK_FENCE));
            }
        }
        for (int[] corner : new int[][]{{-3, -3}, {-3, 3}, {3, -3}, {3, 3}}) {
            changes.add(new BlockBatchUtil.BlockChange(cx + corner[0], y + 1, cz + corner[1],
                    Material.DARK_OAK_LOG));
            changes.add(new BlockBatchUtil.BlockChange(cx + corner[0], y + 2, cz + corner[1],
                    Material.OAK_FENCE));
        }
    }

    private static void buildCagePosts(List<BlockBatchUtil.BlockChange> changes,
                                       int cx, int cz, int baseY) {
        for (int[] offset : new int[][]{{-1, 0}, {1, 0}}) {
            int px = cx + offset[0];
            changes.add(new BlockBatchUtil.BlockChange(px, baseY + 1, cz, Material.BONE_BLOCK));
            changes.add(new BlockBatchUtil.BlockChange(px, baseY + 2, cz, Material.BONE_BLOCK));
            changes.add(new BlockBatchUtil.BlockChange(px, baseY + 3, cz, Material.OAK_FENCE));
        }
        changes.add(new BlockBatchUtil.BlockChange(cx, baseY + 1, cz + 1, Material.OAK_FENCE));
        changes.add(new BlockBatchUtil.BlockChange(cx, baseY + 1, cz - 1, Material.OAK_FENCE));
    }

    // ------------------------------------------------------------------
    // Village: central well, ring of houses, farm plots, fence lamps.
    // ------------------------------------------------------------------

    private static void buildVillage(List<BlockBatchUtil.BlockChange> changes,
                                     int[][] heightmap, int minX, int minZ,
                                     double sx, double sz, SettlementLayout layout, Random random) {
        int x = (int) sx;
        int z = (int) sz;
        int baseY = TerrainHeightmap.getHeight(heightmap, minX, minZ, x, z);
        flattenPad(changes, heightmap, minX, minZ, x, z, 16, baseY);

        buildWell(changes, x, baseY, z);

        double churchBearing = 3 * Math.PI / 2;
        buildChurch(changes, heightmap, minX, minZ, x, z - 11, baseY, layout);

        int houseCount = 3 + random.nextInt(4);
        for (int i = 0; i < houseCount; i++) {
            double angle = (Math.PI * 2 * i) / houseCount + random.nextDouble() * 0.35;
            if (angularDistance(angle, churchBearing) < Math.PI * 0.28) {
                continue;
            }
            double dist = 7 + random.nextDouble() * 3.5;
            int hx = x + (int) Math.round(Math.cos(angle) * dist);
            int hz = z + (int) Math.round(Math.sin(angle) * dist);
            buildHouse(changes, hx, hz, angle, baseY, layout, random);
        }

        buildFarmPlot(changes, x + 4, baseY, z - 3, x + 8, z + 1);
        buildFarmPlot(changes, x - 8, baseY, z - 3, x - 4, z + 1);

        for (int[] lamp : new int[][]{{-4, -4}, {4, -4}, {-4, 4}, {4, 4}}) {
            changes.add(new BlockBatchUtil.BlockChange(x + lamp[0], baseY + 1, z + lamp[1],
                    Material.OAK_FENCE));
            changes.add(new BlockBatchUtil.BlockChange(x + lamp[0], baseY + 2, z + lamp[1],
                    Material.SHROOMLIGHT));
        }

        layout.addMobSite(x, baseY + 1, z, SettlementLayout.StructureSite.Kind.VILLAGE);
    }

    private static double angularDistance(double a, double b) {
        double diff = Math.abs(a - b) % (Math.PI * 2);
        return Math.min(diff, Math.PI * 2 - diff);
    }

    // ------------------------------------------------------------------
    // Church: stone-brick chapel; PvP in or into the nave condemns the aggressor.
    // ------------------------------------------------------------------

    private static void buildChurch(List<BlockBatchUtil.BlockChange> changes,
                                    int[][] heightmap, int minX, int minZ,
                                    int vx, int vz, int baseY, SettlementLayout layout) {
        // 9 wide (x), 11 deep (z), centered on the reserved plot.
        int halfX = 4;
        int halfZ = 5;
        int floorY = baseY + 1;
        int wallTop = baseY + 6;

        flattenPad(changes, heightmap, minX, minZ, vx, vz - 4, 7, baseY);

        for (int dx = -halfX + 1; dx <= halfX - 1; dx++) {
            for (int dz = -halfZ + 1; dz <= halfZ - 1; dz++) {
                changes.add(new BlockBatchUtil.BlockChange(vx + dx, baseY, vz - 4 + dz,
                        Material.STONE_BRICKS));
            }
        }

        for (int dy = floorY; dy <= wallTop; dy++) {
            for (int dx = -halfX; dx <= halfX; dx++) {
                for (int dz = -(halfZ + 4); dz <= halfZ; dz++) {
                    boolean edgeX = Math.abs(dx) == halfX;
                    boolean south = dz == halfZ;
                    boolean north = dz == -(halfZ + 4);
                    if (!edgeX && !south && !north) {
                        continue;
                    }
                    boolean corner = edgeX && (south || north);
                    Material mat = corner ? Material.STONE_BRICKS
                            : (dy >= wallTop ? Material.STONE_BRICK_SLAB : Material.STONE_BRICKS);
                    changes.add(new BlockBatchUtil.BlockChange(vx + dx, dy, vz - 4 + dz, mat));
                }
            }
        }

        addChurchWindow(changes, vx - halfX, floorY + 2, vz - 4, BlockFace.EAST);
        addChurchWindow(changes, vx + halfX, floorY + 2, vz - 4, BlockFace.WEST);
        addChurchWindow(changes, vx, floorY + 2, vz - 4 - halfZ - 4, BlockFace.NORTH);

        // Door on the south face.
        changes.add(new BlockBatchUtil.BlockChange(vx, floorY, vz - 4 + halfZ, Material.SPRUCE_DOOR,
                doorLower(BlockFace.SOUTH)));
        changes.add(new BlockBatchUtil.BlockChange(vx, floorY + 1, vz - 4 + halfZ, Material.SPRUCE_DOOR,
                doorUpper(BlockFace.SOUTH)));

        buildPews(changes, vx, floorY, vz - 4);
        buildAltar(changes, vx, floorY, vz - 8, layout);
        buildSteeple(changes, vx, baseY, vz - 8, halfX, halfZ + 4, wallTop);

        layout.addChurch(
                vx - halfX, baseY, vz - 4 - halfZ - 4,
                vx + halfX, wallTop + 2, vz - 4 + halfZ);
    }

    private static void addChurchWindow(List<BlockBatchUtil.BlockChange> changes,
                                        int x, int y, int z, BlockFace facing) {
        changes.add(new BlockBatchUtil.BlockChange(x, y, z, Material.GLASS_PANE));
        changes.add(new BlockBatchUtil.BlockChange(x, y + 1, z, Material.GLASS_PANE));
        changes.add(new BlockBatchUtil.BlockChange(x, y + 2, z, Material.GLASS_PANE));
        changes.add(new BlockBatchUtil.BlockChange(x, y + 3, z, Material.STONE_BRICK_SLAB));
    }

    private static BlockData doorLower(BlockFace facing) {
        BlockData data = Material.SPRUCE_DOOR.createBlockData();
        if (data instanceof Door door) {
            door.setFacing(facing);
            door.setHalf(Bisected.Half.BOTTOM);
            return data;
        }
        return data;
    }

    private static BlockData doorUpper(BlockFace facing) {
        BlockData data = Material.SPRUCE_DOOR.createBlockData();
        if (data instanceof Door door) {
            door.setFacing(facing);
            door.setHalf(Bisected.Half.TOP);
            return data;
        }
        return data;
    }

    private static void buildPews(List<BlockBatchUtil.BlockChange> changes, int cx, int floorY, int cz) {
        for (int row = 0; row < 3; row++) {
            int z = cz + row * 2 + 1;
            for (int side = -1; side <= 1; side += 2) {
                for (int seat = 1; seat <= 3; seat++) {
                    changes.add(new BlockBatchUtil.BlockChange(cx + side * seat, floorY, z,
                            Material.SPRUCE_STAIRS));
                    changes.add(new BlockBatchUtil.BlockChange(cx + side * seat, floorY + 1, z,
                            Material.SPRUCE_SLAB));
                }
            }
        }
    }

    private static void buildAltar(List<BlockBatchUtil.BlockChange> changes,
                                   int cx, int floorY, int cz, SettlementLayout layout) {
        changes.add(new BlockBatchUtil.BlockChange(cx, floorY, cz, Material.LECTERN));
        changes.add(new BlockBatchUtil.BlockChange(cx, floorY, cz - 1, Material.SOUL_LANTERN));
        changes.add(new BlockBatchUtil.BlockChange(cx - 1, floorY, cz - 1, Material.CHEST));
        layout.addChest(cx - 1, floorY, cz - 1);
        changes.add(new BlockBatchUtil.BlockChange(cx + 1, floorY, cz - 1, Material.CHEST));
        layout.addChest(cx + 1, floorY, cz - 1);
    }

    private static void buildSteeple(List<BlockBatchUtil.BlockChange> changes,
                                     int cx, int baseY, int cz,
                                     int halfX, int halfZ, int wallTop) {
        for (int dy = wallTop + 1; dy <= wallTop + 4; dy++) {
            int inset = (dy - wallTop) / 2;
            for (int dx = -halfX + inset; dx <= halfX - inset; dx++) {
                changes.add(new BlockBatchUtil.BlockChange(cx + dx, dy, cz,
                        Material.STONE_BRICKS));
            }
        }
        changes.add(new BlockBatchUtil.BlockChange(cx, wallTop + 5, cz, Material.BELL));
        changes.add(new BlockBatchUtil.BlockChange(cx, wallTop + 6, cz, Material.STONE_BRICK_SLAB));
        changes.add(new BlockBatchUtil.BlockChange(cx, wallTop + 7, cz, Material.LIGHTNING_ROD));
    }

    private static void buildWell(List<BlockBatchUtil.BlockChange> changes,
                                  int cx, int baseY, int cz) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                changes.add(new BlockBatchUtil.BlockChange(cx + dx, baseY, cz + dz,
                        Material.WATER));
            }
        }
        for (int[] rim : new int[][]{{-2, -2}, {-2, -1}, {-2, 0}, {-2, 1}, {-2, 2},
                {2, -2}, {2, -1}, {2, 0}, {2, 1}, {2, 2},
                {-1, -2}, {0, -2}, {1, -2}, {-1, 2}, {0, 2}, {1, 2}}) {
            changes.add(new BlockBatchUtil.BlockChange(cx + rim[0], baseY, cz + rim[1],
                    Material.COBBLESTONE));
            changes.add(new BlockBatchUtil.BlockChange(cx + rim[0], baseY + 1, cz + rim[1],
                    Material.COBBLESTONE_WALL));
        }
        // Roof posts and slab cap.
        for (int[] post : new int[][]{{-2, -2}, {2, -2}, {-2, 2}, {2, 2}}) {
            changes.add(new BlockBatchUtil.BlockChange(cx + post[0], baseY + 2, cz + post[1],
                    Material.SPRUCE_LOG));
        }
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                changes.add(new BlockBatchUtil.BlockChange(cx + dx, baseY + 3, cz + dz,
                        Material.SPRUCE_SLAB));
            }
        }
    }

    private static void buildHouse(List<BlockBatchUtil.BlockChange> changes,
                                   int cx, int cz, double facing, int baseY,
                                   SettlementLayout layout, Random random) {
        int floorY = baseY + 1;

        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                changes.add(new BlockBatchUtil.BlockChange(cx + dx, floorY - 1, cz + dz,
                        Material.COBBLESTONE));
            }
        }

        for (int dy = 0; dy <= 2; dy++) {
            int y = floorY + dy;
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    boolean wall = Math.abs(dx) == 2 || Math.abs(dz) == 2;
                    if (!wall) {
                        changes.add(new BlockBatchUtil.BlockChange(cx + dx, y, cz + dz, Material.AIR));
                        continue;
                    }
                    boolean corner = Math.abs(dx) == 2 && Math.abs(dz) == 2;
                    if (corner) {
                        changes.add(new BlockBatchUtil.BlockChange(cx + dx, y, cz + dz,
                                Material.SPRUCE_LOG));
                        continue;
                    }
                    boolean doorSide = dz == 2 && dx == 0;
                    boolean window = dy == 1
                            && ((dz == -2 && dx == 0) || (dx == -2 && dz == 0) || (dx == 2 && dz == 0));
                    if (doorSide && dy <= 1) {
                        changes.add(new BlockBatchUtil.BlockChange(cx + dx, y, cz + dz, Material.AIR));
                    } else if (window) {
                        changes.add(new BlockBatchUtil.BlockChange(cx + dx, y, cz + dz,
                                Material.GLASS_PANE));
                    } else {
                        changes.add(new BlockBatchUtil.BlockChange(cx + dx, y, cz + dz,
                                Material.SPRUCE_PLANKS));
                    }
                }
            }
        }

        // Stepped spruce-stair roof.
        StairsData roofStairs = null;
        BlockFace roofFacing = facingToward(Math.round(facing / (Math.PI / 2)) * (Math.PI / 2));
        for (int step = 0; step <= 2; step++) {
            int y = floorY + 3 + step;
            int inset = step;
            for (int a = -2 + inset; a <= 2 - inset; a++) {
                addRoofRow(changes, cx, cz, roofFacing, a, y, inset);
            }
        }

        changes.add(new BlockBatchUtil.BlockChange(cx, floorY + 5, cz, Material.SPRUCE_SLAB));

        changes.add(new BlockBatchUtil.BlockChange(cx, floorY, cz - 1, Material.CHEST));
        layout.addChest(cx, floorY, cz - 1);

        // Torch on one wall inside for light.
        changes.add(new BlockBatchUtil.BlockChange(cx + 1, floorY + 2, cz + 1, Material.TORCH));
    }

    private record StairsData(int placeholder) {
    }

    private static void addRoofRow(List<BlockBatchUtil.BlockChange> changes,
                                   int cx, int cz, BlockFace facing, int along, int y, int inset) {
        // Rows run perpendicular to facing; place stairs facing inward.
        switch (facing) {
            case NORTH, SOUTH -> {
                int zOff = facing == BlockFace.NORTH ? 2 - inset : -(2 - inset);
                changes.add(new BlockBatchUtil.BlockChange(cx + along, y, cz + zOff,
                        Material.SPRUCE_STAIRS, stair(facing)));
                if (inset == 2) {
                    changes.add(new BlockBatchUtil.BlockChange(cx + along, y + 1, cz,
                            Material.SPRUCE_SLAB));
                }
            }
            default -> {
                int xOff = facing == BlockFace.EAST ? 2 - inset : -(2 - inset);
                changes.add(new BlockBatchUtil.BlockChange(cx + xOff, y, cz + along,
                        Material.SPRUCE_STAIRS, stair(facing)));
                if (inset == 2) {
                    changes.add(new BlockBatchUtil.BlockChange(cx, y + 1, cz + along,
                            Material.SPRUCE_SLAB));
                }
            }
        }
    }

    private static BlockData stair(BlockFace facing) {
        BlockData data = Material.SPRUCE_STAIRS.createBlockData();
        if (data instanceof Stairs stairs) {
            stairs.setFacing(facing);
            return data;
        }
        return data;
    }

    private static BlockFace facingToward(double radians) {
        double normalized = (radians % (Math.PI * 2) + Math.PI * 2) % (Math.PI * 2);
        if (normalized < Math.PI / 4 || normalized >= 7 * Math.PI / 4) {
            return BlockFace.EAST;
        }
        if (normalized < 3 * Math.PI / 4) {
            return BlockFace.SOUTH;
        }
        if (normalized < 5 * Math.PI / 4) {
            return BlockFace.WEST;
        }
        return BlockFace.NORTH;
    }

    private static void buildFarmPlot(List<BlockBatchUtil.BlockChange> changes,
                                      int x0, int baseY, int z0, int x1, int z1) {
        for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++) {
            for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++) {
                changes.add(new BlockBatchUtil.BlockChange(x, baseY, z, Material.FARMLAND));
                changes.add(new BlockBatchUtil.BlockChange(x, baseY + 1, z, Material.WHEAT));
            }
        }
        // Log border on the long edges.
        for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++) {
            changes.add(new BlockBatchUtil.BlockChange(x, baseY, z0 - 1, Material.SPRUCE_LOG));
            changes.add(new BlockBatchUtil.BlockChange(x, baseY, z1 + 1, Material.SPRUCE_LOG));
        }
    }

    // ------------------------------------------------------------------
    // Ruins: broken cobble walls with occasional chest.
    // ------------------------------------------------------------------

    private static void buildRuins(List<BlockBatchUtil.BlockChange> changes,
                                   int[][] heightmap, int minX, int minZ,
                                   int sx, int sz, int halfSpan, SettlementLayout layout,
                                   Random random) {
        int baseY = TerrainHeightmap.getHeight(heightmap, minX, minZ, sx, sz);
        flattenPad(changes, heightmap, minX, minZ, sx, sz, halfSpan + 1, baseY);

        for (int side = 0; side < 4; side++) {
            for (int step = 0; step <= halfSpan * 2; step++) {
                if (random.nextDouble() < 0.35) {
                    continue;
                }
                int dx;
                int dz;
                switch (side) {
                    case 0 -> { dx = -halfSpan + step; dz = -halfSpan; }
                    case 1 -> { dx = halfSpan; dz = -halfSpan + step; }
                    case 2 -> { dx = halfSpan - step; dz = halfSpan; }
                    default -> { dx = -halfSpan; dz = halfSpan - step; }
                }
                int height = 1 + random.nextInt(3);
                for (int dy = 1; dy <= height; dy++) {
                    Material mat = random.nextBoolean()
                            ? Material.COBBLESTONE : Material.MOSSY_COBBLESTONE;
                    changes.add(new BlockBatchUtil.BlockChange(sx + dx, baseY + dy, sz + dz, mat));
                }
            }
        }

        if (random.nextBoolean()) {
            changes.add(new BlockBatchUtil.BlockChange(sx, baseY + 1, sz, Material.CHEST));
            layout.addChest(sx, baseY + 1, sz);
        }
    }

    // ------------------------------------------------------------------
    // Shared helpers
    // ------------------------------------------------------------------

    private static void flattenPad(List<BlockBatchUtil.BlockChange> changes,
                                   int[][] heightmap, int minX, int minZ,
                                   int cx, int cz, int padRadius, int targetY) {
        for (int x = cx - padRadius; x <= cx + padRadius; x++) {
            for (int z = cz - padRadius; z <= cz + padRadius; z++) {
                int surfaceY = TerrainHeightmap.getHeight(heightmap, minX, minZ, x, z);
                if (surfaceY > targetY) {
                    for (int y = targetY + 1; y <= surfaceY; y++) {
                        changes.add(new BlockBatchUtil.BlockChange(x, y, z, Material.AIR));
                    }
                } else if (surfaceY < targetY) {
                    for (int y = surfaceY + 1; y < targetY; y++) {
                        changes.add(new BlockBatchUtil.BlockChange(x, y, z, Material.COARSE_DIRT));
                    }
                }
                changes.add(new BlockBatchUtil.BlockChange(x, targetY, z, Material.COARSE_DIRT));
            }
        }
    }

    private static BlockData ladderFacing(BlockFace face) {
        BlockData data = Material.LADDER.createBlockData();
        if (data instanceof Ladder ladder) {
            ladder.setFacing(face);
            return data;
        }
        return data;
    }
}
