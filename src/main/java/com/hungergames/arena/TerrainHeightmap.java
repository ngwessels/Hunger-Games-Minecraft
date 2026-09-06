package com.hungergames.arena;

import com.hungergames.util.CircleUtil;
import com.hungergames.util.NoiseUtil;

public final class TerrainHeightmap {

    private static final double VOLCANO_RELIEF_SCALE = 2.2;

    private TerrainHeightmap() {
    }

    public static int[][] generate(double centerX, double centerZ, int radius,
                                    int minX, int maxX, int minZ, int maxZ,
                                    int baseY, int seed, String terrain) {
        return generate(centerX, centerZ, radius, minX, maxX, minZ, maxZ, baseY, seed, terrain, radius, 0);
    }

    public static int[][] generate(double centerX, double centerZ, int radius,
                                    int minX, int maxX, int minZ, int maxZ,
                                    int baseY, int seed, String terrain,
                                    int forestRadius, int blendWidth) {
        int width = maxX - minX + 1;
        int depth = maxZ - minZ + 1;
        int[][] heights = new int[width][depth];
        int hillRadius = Math.max(20, Math.min(radius, forestRadius));
        int blend = Math.max(0, blendWidth);

        boolean volcano = "volcano".equalsIgnoreCase(terrain);
        double scale = volcano ? 0.0 : 28.0;
        int maxVariation = "barren".equalsIgnoreCase(terrain) ? 1 : 2;

        for (int xi = 0; xi < width; xi++) {
            for (int zi = 0; zi < depth; zi++) {
                int x = minX + xi;
                int z = minZ + zi;
                if (!CircleUtil.isInsideCircle(centerX, centerZ, x, z, radius)) {
                    heights[xi][zi] = baseY;
                    continue;
                }

                if (volcano) {
                    heights[xi][zi] = sampleVolcanoColumn(x, z, centerX, centerZ, hillRadius,
                            radius, blend, baseY, seed);
                } else {
                    int sx = (int) Math.floor(x / scale);
                    int sz = (int) Math.floor(z / scale);
                    double noise = NoiseUtil.fractalNoise2D(sx, sz, seed, 3);
                    heights[xi][zi] = baseY + (int) Math.round(noise * maxVariation);
                }
            }
        }

        heights = smooth(heights, width, depth, volcano ? 1 : 2);
        if (volcano) {
            blendVolcanoCenter(heights, minX, minZ, centerX, centerZ, 11, baseY);
        } else {
            flattenCenter(heights, minX, minZ, centerX, centerZ, 6, baseY);
        }
        return clampHeights(heights, width, depth, baseY);
    }

    private static int[][] clampHeights(int[][] heights, int width, int depth, int maxY) {
        for (int xi = 0; xi < width; xi++) {
            for (int zi = 0; zi < depth; zi++) {
                heights[xi][zi] = Math.min(maxY, Math.max(1, heights[xi][zi]));
            }
        }
        return heights;
    }

    private static int sampleVolcanoColumn(int x, int z, double centerX, double centerZ,
                                           int forestRadius, int arenaRadius, int blendWidth,
                                           int baseY, int seed) {
        double dist = CircleUtil.distanceFromCenter(centerX, centerZ, x, z);
        int forestH = sampleVolcanoHeight(x, z, centerX, centerZ, forestRadius, baseY, seed);
        if (dist <= forestRadius - blendWidth || arenaRadius <= forestRadius) {
            return forestH;
        }
        double arcticN = NoiseUtil.continuousFractalNoise2D(x * 0.05, z * 0.05, seed + 907, 2);
        int arcticH = baseY + (int) Math.round(arcticN * 0.4);
        if (dist >= forestRadius) {
            return arcticH;
        }
        double t = (dist - (forestRadius - blendWidth)) / Math.max(1, blendWidth);
        return (int) Math.round(forestH * (1.0 - t) + arcticH * t);
    }

    private static int sampleVolcanoHeight(int x, int z, double centerX, double centerZ,
                                           int radius, int baseY, int seed) {
        double hills = NoiseUtil.continuousFractalNoise2D(x * 0.038, z * 0.038, seed, 4);
        double detail = NoiseUtil.continuousFractalNoise2D(x * 0.11, z * 0.11, seed + 211, 2) * 0.45;
        double ridges = 1.0 - Math.abs(NoiseUtil.continuousFractalNoise2D(
                x * 0.024 + 40, z * 0.024 - 15, seed + 419, 3));
        ridges = (ridges - 0.35) * 1.4;

        double dist = CircleUtil.distanceFromCenter(centerX, centerZ, x, z);
        double edgeFactor = Math.min(1.0, dist / (radius * 0.85));
        double calderaDip = Math.max(0, 1.0 - dist / 18.0);
        calderaDip = calderaDip * calderaDip * 2.5;

        double combined = hills * 0.5 + detail + ridges * 0.35;
        int heightOffset = (int) Math.round(combined * VOLCANO_RELIEF_SCALE * (0.35 + edgeFactor * 0.65) - calderaDip);
        return baseY + heightOffset;
    }

    private static void blendVolcanoCenter(int[][] heights, int minX, int minZ,
                                           double centerX, double centerZ, int blendRadius, int baseY) {
        int icx = (int) centerX;
        int icz = (int) centerZ;
        int targetY = getHeight(heights, minX, minZ, icx, icz);
        if (targetY <= 0) {
            targetY = baseY;
        }

        for (int dx = -blendRadius; dx <= blendRadius; dx++) {
            for (int dz = -blendRadius; dz <= blendRadius; dz++) {
                double dist = Math.sqrt(dx * dx + dz * dz);
                if (dist > blendRadius) {
                    continue;
                }
                double weight = 1.0 - dist / blendRadius;
                weight = weight * weight;

                int xi = icx + dx - minX;
                int zi = icz + dz - minZ;
                if (xi < 0 || zi < 0 || xi >= heights.length || zi >= heights[0].length) {
                    continue;
                }
                int current = heights[xi][zi];
                heights[xi][zi] = (int) Math.round(current * (1.0 - weight) + targetY * weight);
            }
        }
    }

    public static int getHeight(int[][] heights, int minX, int minZ, int x, int z) {
        int xi = x - minX;
        int zi = z - minZ;
        if (xi < 0 || zi < 0 || xi >= heights.length || zi >= heights[0].length) {
            return 0;
        }
        return heights[xi][zi];
    }

    private static int[][] smooth(int[][] heights, int width, int depth, int passes) {
        int[][] current = heights;
        for (int p = 0; p < passes; p++) {
            int[][] next = new int[width][depth];
            for (int xi = 0; xi < width; xi++) {
                for (int zi = 0; zi < depth; zi++) {
                    int sum = 0;
                    int count = 0;
                    for (int dx = -1; dx <= 1; dx++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            int nx = xi + dx;
                            int nz = zi + dz;
                            if (nx >= 0 && nx < width && nz >= 0 && nz < depth) {
                                sum += current[nx][nz];
                                count++;
                            }
                        }
                    }
                    next[xi][zi] = Math.round((float) sum / count);
                }
            }
            current = next;
        }
        return current;
    }

    private static void flattenCenter(int[][] heights, int minX, int minZ,
                                      double centerX, double centerZ, int flatRadius, int baseY) {
        int icx = (int) centerX;
        int icz = (int) centerZ;
        int sum = 0;
        int count = 0;
        for (int dx = -flatRadius; dx <= flatRadius; dx++) {
            for (int dz = -flatRadius; dz <= flatRadius; dz++) {
                if (dx * dx + dz * dz <= flatRadius * flatRadius) {
                    int h = getHeight(heights, minX, minZ, icx + dx, icz + dz);
                    if (h > 0) {
                        sum += h;
                        count++;
                    }
                }
            }
        }
        if (count == 0) {
            return;
        }
        int flatY = sum / count;
        for (int dx = -flatRadius; dx <= flatRadius; dx++) {
            for (int dz = -flatRadius; dz <= flatRadius; dz++) {
                if (dx * dx + dz * dz <= flatRadius * flatRadius) {
                    int xi = icx + dx - minX;
                    int zi = icz + dz - minZ;
                    if (xi >= 0 && xi < heights.length && zi >= 0 && zi < heights[0].length) {
                        heights[xi][zi] = flatY;
                    }
                }
            }
        }
    }

    public static boolean isFlatArea(int[][] heights, int minX, int minZ, int x, int z, int maxDelta) {
        int center = getHeight(heights, minX, minZ, x, z);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (Math.abs(getHeight(heights, minX, minZ, x + dx, z + dz) - center) > maxDelta) {
                    return false;
                }
            }
        }
        return true;
    }
}
