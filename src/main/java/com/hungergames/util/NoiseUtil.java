package com.hungergames.util;

public final class NoiseUtil {

    private NoiseUtil() {
    }

    public static double noise2D(int x, int z, int seed) {
        int n = x + z * 57 + seed * 131;
        n = (n << 13) ^ n;
        return 1.0 - ((n * (n * n * 15731 + 789221) + 1376312589) & 0x7fffffff) / 1073741824.0;
    }

    public static double fractalNoise2D(int x, int z, int seed, int octaves) {
        double total = 0;
        double amplitude = 1;
        double max = 0;
        int frequency = 1;
        for (int i = 0; i < octaves; i++) {
            total += noise2D(x * frequency, z * frequency, seed + i * 1013) * amplitude;
            max += amplitude;
            amplitude *= 0.5;
            frequency *= 2;
        }
        return total / max;
    }

    public static double continuousNoise2D(double x, double z, int seed) {
        int x0 = (int) Math.floor(x);
        int z0 = (int) Math.floor(z);
        double tx = smoothStep(x - x0);
        double tz = smoothStep(z - z0);

        double n00 = noise2D(x0, z0, seed);
        double n10 = noise2D(x0 + 1, z0, seed);
        double n01 = noise2D(x0, z0 + 1, seed);
        double n11 = noise2D(x0 + 1, z0 + 1, seed);

        double ix0 = n00 + (n10 - n00) * tx;
        double ix1 = n01 + (n11 - n01) * tx;
        return ix0 + (ix1 - ix0) * tz;
    }

    public static double continuousFractalNoise2D(double x, double z, int seed, int octaves) {
        double total = 0;
        double amplitude = 1;
        double max = 0;
        double frequency = 1;
        for (int i = 0; i < octaves; i++) {
            total += continuousNoise2D(x * frequency, z * frequency, seed + i * 1013) * amplitude;
            max += amplitude;
            amplitude *= 0.5;
            frequency *= 2;
        }
        return total / max;
    }

    private static double smoothStep(double t) {
        return t * t * (3 - 2 * t);
    }
}
