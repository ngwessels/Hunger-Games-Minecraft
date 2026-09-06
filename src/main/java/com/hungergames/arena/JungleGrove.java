package com.hungergames.arena;

import com.hungergames.gamemode.GameMode;
import com.hungergames.gamemode.JungleGroveConfig;
import com.hungergames.util.BlockBatchUtil;
import com.hungergames.util.CircleUtil;
import com.hungergames.util.NoiseUtil;
import com.hungergames.util.SpawnLocationUtil;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.MultipleFacing;
import org.bukkit.block.data.type.Ladder;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Deterministic jungle grove for large volcano arenas: tall mega jungle trees
 * surrounding a connected multi-tree treehouse. Appended after the standard
 * tree pass so its block changes override the volcanic surface.
 */
public final class JungleGrove {

    /** Grove outer edge never reaches beyond this fraction of the arena radius. */
    private static final double MAX_EDGE_FACTOR = 0.72;
    private static final long CENTER_SALT = 0x51ED270BL;
    private static final long BUILD_SALT = 0xA5F1523DL;
    private static final BlockFace[] CARDINALS = {
            BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST
    };

    private JungleGrove() {
    }

    public static boolean isActive(Arena arena, GameMode gameMode) {
        if (arena == null || gameMode == null
                || !"volcano".equalsIgnoreCase(gameMode.getInitialTerrain())) {
            return false;
        }
        JungleGroveConfig config = gameMode.getJungleGrove();
        return config != null && config.isEnabled()
                && gameMode.getForestRadius() >= config.getMinRadius();
    }

    public static double[] groveCenter(Arena arena, GameMode gameMode, JungleGroveConfig config) {
        Random random = new Random(arena.getTerrainSeed() * 31L + CENTER_SALT);
        double angle = random.nextDouble() * Math.PI * 2;
        double distance = gameMode.getForestRadius() * config.getCenterOffsetFactor();
        return new double[]{
                arena.getCenterX() + Math.cos(angle) * distance,
                arena.getCenterZ() + Math.sin(angle) * distance
        };
    }

    public static int effectiveGroveRadius(Arena arena, GameMode gameMode, JungleGroveConfig config) {
        double maxByEdge = (MAX_EDGE_FACTOR - config.getCenterOffsetFactor()) * gameMode.getForestRadius();
        return (int) Math.max(8, Math.min(config.getGroveRadius(), maxByEdge));
    }

    /** {centerX, centerZ, radius} when the grove is active for this arena, else null. */
    public static double[] groveArea(Arena arena, GameMode gameMode) {
        if (!isActive(arena, gameMode)) {
            return null;
        }
        JungleGroveConfig config = gameMode.getJungleGrove();
        double[] center = groveCenter(arena, gameMode, config);
        return new double[]{center[0], center[1], effectiveGroveRadius(arena, gameMode, config)};
    }

    public static boolean isInside(double[] groveArea, double x, double z) {
        return groveArea != null
                && CircleUtil.isInsideCircle(groveArea[0], groveArea[1], x, z, groveArea[2]);
    }

    public static void addJungleGrove(List<BlockBatchUtil.BlockChange> changes, Arena arena,
                                      GameMode gameMode, int[][] heightmap, int minX, int minZ) {
        double[] area = groveArea(arena, gameMode);
        if (area == null) {
            return;
        }
        JungleGroveConfig config = gameMode.getJungleGrove();
        double gx = area[0];
        double gz = area[1];
        int groveRadius = (int) area[2];
        Random random = new Random(arena.getTerrainSeed() * 961748941L + BUILD_SALT);

        plantGroundCover(changes, heightmap, minX, minZ, gx, gz, groveRadius);

        List<GroveTree> trees = layoutMegaTrees(arena, config, heightmap, minX, minZ,
                gx, gz, groveRadius, gameMode.getForestRadius(), random);
        for (GroveTree tree : trees) {
            buildMegaTree(changes, tree, random);
        }

        plantFillerTrees(changes, heightmap, minX, minZ, gx, gz, groveRadius, trees, random);

        int anchors = Math.min(config.getTreehouseAnchors(), trees.size());
        buildTreehouse(changes, trees.subList(0, anchors), heightmap, minX, minZ);
    }

    private record GroveTree(int x, int z, int baseY, int trunkHeight) {
    }

    private static void plantGroundCover(List<BlockBatchUtil.BlockChange> changes,
                                         int[][] heightmap, int minX, int minZ,
                                         double gx, double gz, int groveRadius) {
        int bound = groveRadius + 1;
        int minXScan = (int) Math.floor(gx - bound);
        int maxXScan = (int) Math.ceil(gx + bound);
        int minZScan = (int) Math.floor(gz - bound);
        int maxZScan = (int) Math.ceil(gz + bound);
        for (int x = minXScan; x <= maxXScan; x++) {
            for (int z = minZScan; z <= maxZScan; z++) {
                if (!CircleUtil.isInsideCircle(gx, gz, x, z, groveRadius)) {
                    continue;
                }
                int surfaceY = TerrainHeightmap.getHeight(heightmap, minX, minZ, x, z);
                Material soil = noise01(x, z) > 0.55 ? Material.PODZOL : Material.GRASS_BLOCK;
                changes.add(new BlockBatchUtil.BlockChange(x, surfaceY, z, soil));
                double decor = noise01(x + 71, z + 37);
                if (decor > 0.78) {
                    changes.add(new BlockBatchUtil.BlockChange(x, surfaceY + 1, z, Material.FERN));
                } else if (decor > 0.60) {
                    changes.add(new BlockBatchUtil.BlockChange(x, surfaceY + 1, z, Material.SHORT_GRASS));
                } else if (decor > 0.52) {
                    changes.add(new BlockBatchUtil.BlockChange(x, surfaceY + 1, z, Material.MOSS_CARPET));
                }
            }
        }
    }

    private static double noise01(int x, int z) {
        return (NoiseUtil.continuousNoise2D(x * 0.18, z * 0.18, 7717) + 1.0) / 2.0;
    }

    private static List<GroveTree> layoutMegaTrees(Arena arena, JungleGroveConfig config,
                                                   int[][] heightmap, int minX, int minZ,
                                                   double gx, double gz, int groveRadius,
                                                   int forestRadius, Random random) {
        List<GroveTree> trees = new ArrayList<>();
        int count = config.getMegaTreeCount();
        double goldenAngle = 2.39996322972865332;
        double spread = Math.max(4, groveRadius - 6);
        int heightRange = Math.max(1, config.getMaxTrunkHeight() - config.getMinTrunkHeight());

        for (int i = 0; i < count; i++) {
            double angle = i * goldenAngle + (random.nextDouble() - 0.5) * 0.5;
            double dist = Math.sqrt((i + 0.5) / count) * spread;
            int x = (int) Math.round(gx + Math.cos(angle) * dist);
            int z = (int) Math.round(gz + Math.sin(angle) * dist);
            if (SpawnLocationUtil.isOnSpawnRing(x, z, arena.getCenterX(), arena.getCenterZ(),
                    forestRadius)
                    || VolcanoCaldera.isInsideCalderaArea(arena.getCenterX(), arena.getCenterZ(), x, z)) {
                continue;
            }
            int groundY = TerrainHeightmap.getHeight(heightmap, minX, minZ, x, z);
            int trunkHeight = config.getMinTrunkHeight() + random.nextInt(heightRange + 1);
            trees.add(new GroveTree(x, z, groundY + 1, trunkHeight));
        }
        trees.sort((a, b) -> Integer.compare(b.trunkHeight(), a.trunkHeight()));
        return trees;
    }

    private static void buildMegaTree(List<BlockBatchUtil.BlockChange> changes, GroveTree tree,
                                      Random random) {
        int topY = tree.baseY() + tree.trunkHeight();

        for (int dy = 0; dy < tree.trunkHeight(); dy++) {
            for (int dx = 0; dx <= 1; dx++) {
                for (int dz = 0; dz <= 1; dz++) {
                    changes.add(new BlockBatchUtil.BlockChange(
                            tree.x() + dx, tree.baseY() + dy, tree.z() + dz, Material.JUNGLE_LOG));
                }
            }
        }

        int branchCount = 2 + random.nextInt(2);
        int lowestBranch = tree.baseY() + Math.max(4, tree.trunkHeight() / 2);
        for (int b = 0; b < branchCount && lowestBranch < topY - 2; b++) {
            int by = lowestBranch + random.nextInt(topY - 2 - lowestBranch);
            BlockFace dir = CARDINALS[random.nextInt(CARDINALS.length)];
            int bx = tree.x() + (dir == BlockFace.EAST ? 2 : dir == BlockFace.WEST ? -1 : 0);
            int bz = tree.z() + (dir == BlockFace.SOUTH ? 2 : dir == BlockFace.NORTH ? -1 : 0);
            changes.add(new BlockBatchUtil.BlockChange(bx, by, bz, Material.JUNGLE_LOG));
            changes.add(new BlockBatchUtil.BlockChange(
                    bx + dir.getModX(), by + 1, bz + dir.getModZ(), Material.JUNGLE_LOG));
            addLeafBlob(changes, bx + dir.getModX() * 2, by + 1, bz + dir.getModZ() * 2);
        }

        int[][] canopyLayers = {{3, 1}, {4, 2}, {4, 3}, {3, 4}, {2, 5}};
        for (int[] layer : canopyLayers) {
            addCanopyLayer(changes, tree.x(), topY + layer[1], tree.z(), layer[0]);
        }
        changes.add(new BlockBatchUtil.BlockChange(tree.x(), topY + 6, tree.z(),
                Material.JUNGLE_LEAVES, persistentLeaves(Material.JUNGLE_LEAVES)));

        for (int vy = tree.baseY() + 3; vy < topY - 2; vy += 3) {
            BlockFace dir = CARDINALS[random.nextInt(CARDINALS.length)];
            int hangLength = 2 + random.nextInt(3);
            int vx = tree.x() + (dir == BlockFace.EAST ? 2
                    : dir == BlockFace.WEST ? -1 : random.nextInt(2));
            int vz = tree.z() + (dir == BlockFace.SOUTH ? 2
                    : dir == BlockFace.NORTH ? -1 : random.nextInt(2));
            for (int dy = 0; dy < hangLength && vy - dy > tree.baseY(); dy++) {
                changes.add(new BlockBatchUtil.BlockChange(vx, vy - dy, vz, Material.VINE,
                        vineData(dir.getOppositeFace())));
            }
        }
    }

    private static void addLeafBlob(List<BlockBatchUtil.BlockChange> changes,
                                    int cx, int cy, int cz) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    if (Math.abs(dx) + Math.abs(dz) + Math.abs(dy) > 2) {
                        continue;
                    }
                    changes.add(new BlockBatchUtil.BlockChange(cx + dx, cy + dy, cz + dz,
                            Material.JUNGLE_LEAVES, persistentLeaves(Material.JUNGLE_LEAVES)));
                }
            }
        }
    }

    private static void addCanopyLayer(List<BlockBatchUtil.BlockChange> changes,
                                       int cx, int cy, int cz, int radius) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radius * radius + 1) {
                    continue;
                }
                changes.add(new BlockBatchUtil.BlockChange(cx + dx, cy, cz + dz,
                        Material.JUNGLE_LEAVES, persistentLeaves(Material.JUNGLE_LEAVES)));
            }
        }
    }

    private static void plantFillerTrees(List<BlockBatchUtil.BlockChange> changes,
                                         int[][] heightmap, int minX, int minZ,
                                         double gx, double gz, int groveRadius,
                                         List<GroveTree> megaTrees, Random random) {
        int target = 6 + random.nextInt(4);
        int placed = 0;
        for (int attempt = 0; attempt < target * 3 && placed < target; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double dist = random.nextDouble() * (groveRadius - 3);
            int x = (int) Math.round(gx + Math.cos(angle) * dist);
            int z = (int) Math.round(gz + Math.sin(angle) * dist);
            boolean clashes = false;
            for (GroveTree tree : megaTrees) {
                int dx = x - tree.x();
                int dz = z - tree.z();
                if (dx * dx + dz * dz < 25) {
                    clashes = true;
                    break;
                }
            }
            if (clashes) {
                continue;
            }
            int groundY = TerrainHeightmap.getHeight(heightmap, minX, minZ, x, z);
            buildFillerTree(changes, x, groundY + 1, z, 6 + random.nextInt(5), random);
            placed++;
        }
    }

    private static void buildFillerTree(List<BlockBatchUtil.BlockChange> changes,
                                        int x, int baseY, int z, int height, Random random) {
        for (int dy = 0; dy < height; dy++) {
            changes.add(new BlockBatchUtil.BlockChange(x, baseY + dy, z, Material.JUNGLE_LOG));
        }
        int topY = baseY + height;
        addCanopyLayer(changes, x, topY, z, 3);
        addCanopyLayer(changes, x, topY + 1, z, 2);
        addCanopyLayer(changes, x, topY + 2, z, 1);
        changes.add(new BlockBatchUtil.BlockChange(x, topY + 3, z, Material.JUNGLE_LEAVES,
                persistentLeaves(Material.JUNGLE_LEAVES)));
        if (random.nextBoolean()) {
            BlockFace dir = CARDINALS[random.nextInt(CARDINALS.length)];
            changes.add(new BlockBatchUtil.BlockChange(
                    x + dir.getModX(), topY - 2 - random.nextInt(2), z + dir.getModZ(),
                    Material.VINE, vineData(dir.getOppositeFace())));
        }
    }

    private static void buildTreehouse(List<BlockBatchUtil.BlockChange> changes,
                                       List<GroveTree> anchors, int[][] heightmap,
                                       int minX, int minZ) {
        if (anchors.size() < 2) {
            return;
        }
        // One shared deck level: every deck, bridge, and hut floor sits at this Y,
        // keeping all walkways flat and genuinely connected.
        int sharedDeck = Integer.MAX_VALUE;
        for (GroveTree tree : anchors) {
            sharedDeck = Math.min(sharedDeck, tree.baseY() + Math.max(6, tree.trunkHeight() / 2));
        }

        for (GroveTree tree : anchors) {
            buildDeck(changes, tree, sharedDeck);
            buildAccessLadder(changes, tree, sharedDeck);
        }

        for (int i = 0; i < anchors.size(); i++) {
            GroveTree from = anchors.get(i);
            GroveTree to = anchors.get((i + 1) % anchors.size());
            buildBridge(changes, from, sharedDeck, to, heightmap, minX, minZ);
        }

        int[] hut = buildCentralHut(changes, anchors, sharedDeck, heightmap, minX, minZ);
        GroveTree[] trunkFootprints = anchors.toArray(new GroveTree[0]);
        for (GroveTree tree : anchors) {
            buildWalkway(changes, hut[0], hut[1], sharedDeck,
                    tree.x() + 1, tree.z() + 1, sharedDeck, heightmap, minX, minZ,
                    hut[0], hut[1], trunkFootprints);
        }
    }

    private static void buildDeck(List<BlockBatchUtil.BlockChange> changes,
                                  GroveTree tree, int deckY) {
        for (int dx = -2; dx <= 3; dx++) {
            for (int dz = -2; dz <= 3; dz++) {
                if (isInsideTrunkFootprint(tree, tree.x() + dx, tree.z() + dz)) {
                    continue;
                }
                if (dx == 2 && dz == 0) {
                    changes.add(new BlockBatchUtil.BlockChange(
                            tree.x() + dx, deckY, tree.z() + dz, Material.AIR));
                    continue;
                }
                changes.add(new BlockBatchUtil.BlockChange(
                        tree.x() + dx, deckY, tree.z() + dz, Material.JUNGLE_PLANKS));
            }
        }
        int[][] cornerPosts = {{-2, -2}, {-2, 3}, {3, -2}, {3, 3}};
        for (int[] post : cornerPosts) {
            changes.add(new BlockBatchUtil.BlockChange(
                    tree.x() + post[0], deckY + 1, tree.z() + post[1], Material.JUNGLE_FENCE));
        }
    }

    private static void buildAccessLadder(List<BlockBatchUtil.BlockChange> changes,
                                          GroveTree tree, int deckY) {
        int lx = tree.x() + 2;
        int lz = tree.z();
        for (int y = tree.baseY(); y <= deckY; y++) {
            changes.add(new BlockBatchUtil.BlockChange(lx, y, lz, Material.LADDER,
                    ladderData(BlockFace.WEST)));
        }
    }

    private static void buildBridge(List<BlockBatchUtil.BlockChange> changes,
                                    GroveTree from, int deckY, GroveTree to,
                                    int[][] heightmap, int minX, int minZ) {
        buildWalkway(changes, from.x() + 1, from.z() + 1, deckY,
                to.x() + 1, to.z() + 1, deckY, heightmap, minX, minZ,
                Integer.MIN_VALUE, Integer.MIN_VALUE, new GroveTree[]{from, to});
    }

    /**
     * Flat fenced plank walkway between two points at the same deck level.
     * Railing is deliberately light: one continuous fence line on a single
     * side (alternating lone posts on diagonals). Support posts are rare and
     * only where the walkway actually clears the ground.
     */
    private static void buildWalkway(List<BlockBatchUtil.BlockChange> changes,
                                     int ax, int az, int ay, int bx, int bz, int by,
                                     int[][] heightmap, int minX, int minZ,
                                     int skipX, int skipZ, GroveTree[] trunkFootprints) {
        int length = Math.max(1, Math.max(Math.abs(bx - ax), Math.abs(bz - az)));
        if (length == 1 && ax == bx && az == bz) {
            return;
        }
        int y = ay;

        int px = Integer.signum(bx - ax);
        int pz = Integer.signum(bz - az);
        boolean diagonal = px != 0 && pz != 0;

        for (int step = 0; step <= length; step++) {
            double t = (double) step / length;
            int x = (int) Math.round(ax + (bx - ax) * t);
            int z = (int) Math.round(az + (bz - az) * t);

            if ((Math.abs(x - skipX) < 2 && Math.abs(z - skipZ) < 2)
                    || isInsideAnyTrunk(trunkFootprints, x, z)) {
                continue;
            }

            changes.add(new BlockBatchUtil.BlockChange(x, y, z, Material.JUNGLE_PLANKS));

            boolean nearEndpoint = step < 2 || step > length - 2;
            if (!nearEndpoint) {
                if (diagonal) {
                    // Alternate a single post side to side on diagonals.
                    BlockFace side = (step / 2) % 2 == 0 ? CARDINALS[0] : CARDINALS[1];
                    changes.add(new BlockBatchUtil.BlockChange(
                            x + side.getModX(), y + 1, z + side.getModZ(), Material.JUNGLE_FENCE));
                } else {
                    // One continuous rail on a single side keeps bridges readable
                    // without walling them in.
                    changes.add(new BlockBatchUtil.BlockChange(
                            x + (px != 0 ? 0 : 1), y + 1,
                            z + (px != 0 ? 1 : 0), Material.JUNGLE_FENCE));
                }
            }

            if (step % 8 == 0) {
                int groundY = TerrainHeightmap.getHeight(heightmap, minX, minZ, x, z);
                if (y - groundY >= 4) {
                    for (int py = groundY + 1; py < y; py++) {
                        changes.add(new BlockBatchUtil.BlockChange(
                                x, py, z, Material.STRIPPED_JUNGLE_LOG));
                    }
                }
            }
        }
    }

    private static boolean isInsideTrunkFootprint(GroveTree tree, int x, int z) {
        int dx = x - tree.x();
        int dz = z - tree.z();
        return dx >= 0 && dx <= 1 && dz >= 0 && dz <= 1;
    }

    private static boolean isInsideAnyTrunk(GroveTree[] trees, int x, int z) {
        if (trees == null) {
            return false;
        }
        for (GroveTree tree : trees) {
            if (isInsideTrunkFootprint(tree, x, z)) {
                return true;
            }
        }
        return false;
    }

    /** Builds the elevated hut; returns {floorCenterX, floorCenterZ}. */
    private static int[] buildCentralHut(List<BlockBatchUtil.BlockChange> changes,
                                         List<GroveTree> anchors, int deckY,
                                         int[][] heightmap, int minX, int minZ) {
        int sumX = 0;
        int sumZ = 0;
        for (GroveTree tree : anchors) {
            sumX += tree.x();
            sumZ += tree.z();
        }
        int hx = sumX / anchors.size();
        int hz = sumZ / anchors.size();

        // Flush with the shared deck so walkways enter straight through doorways.
        int floorY = deckY;

        int groundY = TerrainHeightmap.getHeight(heightmap, minX, minZ, hx, hz);
        int[][] posts = {{-2, -2}, {2, -2}, {-2, 2}, {2, 2}};
        for (int[] post : posts) {
            for (int y = groundY + 1; y <= floorY; y++) {
                changes.add(new BlockBatchUtil.BlockChange(
                        hx + post[0], y, hz + post[1], Material.STRIPPED_JUNGLE_LOG));
            }
        }

        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                changes.add(new BlockBatchUtil.BlockChange(hx + dx, floorY, hz + dz,
                        Material.JUNGLE_PLANKS));
            }
        }

        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                boolean perimeter = Math.abs(dx) == 2 || Math.abs(dz) == 2;
                if (!perimeter) {
                    for (int dy = 1; dy <= 3; dy++) {
                        changes.add(new BlockBatchUtil.BlockChange(hx + dx, floorY + dy, hz + dz,
                                Material.AIR));
                    }
                    continue;
                }
                boolean corner = Math.abs(dx) == 2 && Math.abs(dz) == 2;
                boolean doorway = isDoorway(dx, dz);
                for (int dy = 1; dy <= 3; dy++) {
                    Material material = Material.JUNGLE_PLANKS;
                    if (corner) {
                        material = Material.JUNGLE_WOOD;
                    } else if (doorway && dy <= 2) {
                        material = Material.AIR;
                    } else if (!corner && isWindow(dx, dz) && dy == 2) {
                        material = Material.AIR;
                    }
                    changes.add(new BlockBatchUtil.BlockChange(hx + dx, floorY + dy, hz + dz,
                            material));
                }
            }
        }

        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                changes.add(new BlockBatchUtil.BlockChange(hx + dx, floorY + 4, hz + dz,
                        Material.JUNGLE_PLANKS));
            }
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                changes.add(new BlockBatchUtil.BlockChange(hx + dx, floorY + 5, hz + dz,
                        Material.JUNGLE_PLANKS));
            }
        }
        changes.add(new BlockBatchUtil.BlockChange(hx, floorY + 6, hz, Material.JUNGLE_WOOD));
        return new int[]{hx, hz};
    }

    /** Doorways on each face so walkways from every anchor deck can reach the hut. */
    private static boolean isDoorway(int dx, int dz) {
        return (dz == 0 && Math.abs(dx) == 2) || (dx == 0 && Math.abs(dz) == 2);
    }

    private static boolean isWindow(int dx, int dz) {
        return (dx == 0 && Math.abs(dz) == 2) || (dz == 0 && Math.abs(dx) == 2);
    }

    private static BlockData persistentLeaves(Material leavesMaterial) {
        return TreePlacement.persistentLeaves(leavesMaterial);
    }

    /** Vine data whose set face points at the supporting wall (toward the trunk). */
    private static BlockData vineData(BlockFace towardWall) {
        BlockData data = Material.VINE.createBlockData();
        if (data instanceof MultipleFacing vine) {
            vine.setFace(towardWall, true);
            return vine;
        }
        return data;
    }

    private static BlockData ladderData(BlockFace towardWall) {
        BlockData data = Material.LADDER.createBlockData();
        if (data instanceof Ladder ladder) {
            ladder.setFacing(towardWall.getOppositeFace());
            return ladder;
        }
        return data;
    }
}
