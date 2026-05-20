package com.github.xandergos.terraindiffusionmc.pipeline;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Rule-based biome classifier port of _classify_biome in minecraft_api.py.
 *
 * <p>Uses fixed-seed FastNoiseLite instances for climate and elevation noise perturbations.
 * Biome IDs match the Python server's _BIOME_ID mapping.
 */
public final class BiomeClassifier {

    // Fixed-seed noise instances (matching Python's module-level _TEMP_NOISE etc.)
    private static final FastNoiseLite TEMP_NOISE, TEMP_NOISE_FINE;
    private static final FastNoiseLite PRECIP_NOISE;
    private static final FastNoiseLite SNOW_NOISE, SNOW_NOISE_FINE;

    static {
        TEMP_NOISE = makeFnl(12345, 1f/500f, 3, 2f, 0.5f);
        TEMP_NOISE_FINE = makeFnl(54321, 1f/128f, 2, 2f, 0.5f);
        PRECIP_NOISE = makeFnl(12345, 1f/500f, 5, 2f, 0.5f);
        SNOW_NOISE = makeFnl(12345, 1f/500f, 3, 2f, 0.5f);
        SNOW_NOISE_FINE = makeFnl(54321, 1f/128f, 2, 2f, 0.5f);
    }

    private static FastNoiseLite makeFnl(int seed, float freq, int oct, float lac, float gain) {
        FastNoiseLite fnl = new FastNoiseLite(seed);
        fnl.SetNoiseType(FastNoiseLite.NoiseType.Perlin);
        fnl.SetFrequency(freq);
        fnl.SetFractalType(FastNoiseLite.FractalType.FBm);
        fnl.SetFractalOctaves(oct);
        fnl.SetFractalLacunarity(lac);
        fnl.SetFractalGain(gain);
        return fnl;
    }

    // Biome IDs
    static final short PLAINS = 1, SNOWY_PLAINS = 3, DESERT = 5, SWAMP = 6;
    static final short FOREST = 8, TAIGA = 15, SNOWY_TAIGA = 16, SAVANNA = 17;
    static final short WINDSWEPT_HILLS = 19, JUNGLE = 23, BADLANDS = 26, MEADOW = 29;
    static final short GROVE = 31, SNOWY_SLOPES = 32, FROZEN_PEAKS = 33, STONY_PEAKS = 35;
    static final short WARM_OCEAN = 41, OCEAN = 44, COLD_OCEAN = 46, FROZEN_OCEAN = 48;
    static final short FOREST_SPARSE = 108, TAIGA_SPARSE = 115, SNOWY_TAIGA_SPARSE = 116;

    // --- Climate condition bits ---
    // Terrain (mutually exclusive; ocean overrides lowland when elevVal < 0)
    static final int BIT_OCEAN    = 1 << 0;
    static final int BIT_LOWLAND  = 1 << 1;  // altM < 200, non-ocean
    static final int BIT_MIDLAND  = 1 << 2;  // 200 <= altM < 2500, non-ocean
    static final int BIT_MOUNTAIN = 1 << 3;  // altM >= 2500

    // Temperature bands (mutually exclusive)
    static final int BIT_FROZEN    = 1 << 4;  // temp < -5
    static final int BIT_COLD      = 1 << 5;  // -5 <= temp < 5
    static final int BIT_COOL      = 1 << 6;  // 5 <= temp < 12
    static final int BIT_TEMPERATE = 1 << 7;  // 12 <= temp < 20
    static final int BIT_WARM      = 1 << 8;  // 20 <= temp < 26
    static final int BIT_HOT       = 1 << 9;  // temp >= 26

    // Slope state (mutually exclusive)
    static final int BIT_SLOPE_NONE   = 1 << 10;
    static final int BIT_SLOPE_MEDIUM = 1 << 11;
    static final int BIT_SLOPE_BARE   = 1 << 12;

    // Tree coverage after slope overrides (mutually exclusive)
    static final int BIT_TREES_NONE       = 1 << 13;
    static final int BIT_TREES_SPARSE     = 1 << 14;
    static final int BIT_TREES_FOREST     = 1 << 15;
    static final int BIT_TREES_DENSE      = 1 << 16;
    static final int BIT_TREES_RAINFOREST = 1 << 17;

    // Binary condition pairs — every location has exactly one from each pair
    static final int BIT_SNOW             = 1 << 18;
    static final int BIT_NO_SNOW          = 1 << 19;
    static final int BIT_BARREN           = 1 << 20;  // tooArid || tooCold
    static final int BIT_NOT_BARREN       = 1 << 21;
    static final int BIT_LOW_MOISTURE     = 1 << 22;  // treeMoisture < 0.35 || precip < 350
    static final int BIT_NOT_LOW_MOISTURE = 1 << 23;
    static final int BIT_LOW_SEASON       = 1 << 24;  // tStd < 5
    static final int BIT_NOT_LOW_SEASON   = 1 << 25;

    // All mutually exclusive condition groups. For each group, a location has exactly
    // one bit set. addBiome uses these to expand OR conditions and "don't care" groups.
    private static final int[][] CONDITION_GROUPS = {
        {BIT_OCEAN, BIT_LOWLAND, BIT_MIDLAND, BIT_MOUNTAIN},
        {BIT_FROZEN, BIT_COLD, BIT_COOL, BIT_TEMPERATE, BIT_WARM, BIT_HOT},
        {BIT_SLOPE_NONE, BIT_SLOPE_MEDIUM, BIT_SLOPE_BARE},
        {BIT_TREES_NONE, BIT_TREES_SPARSE, BIT_TREES_FOREST, BIT_TREES_DENSE, BIT_TREES_RAINFOREST},
        {BIT_SNOW, BIT_NO_SNOW},
        {BIT_BARREN, BIT_NOT_BARREN},
        {BIT_LOW_MOISTURE, BIT_NOT_LOW_MOISTURE},
        {BIT_LOW_SEASON, BIT_NOT_LOW_SEASON},
    };

    // Maps a fully-specified location condition mask to the biome IDs valid there.
    // Every bit group always has exactly one bit set in the key.
    private static final Map<Integer, short[]> BIOME_MAP;

    /**
     * Registers a biome for all condition combinations described by {@code conditions}.
     *
     * <p>For each condition group, the bits present in {@code conditions} form an OR:
     * the biome is registered for every combination of one bit per group. Groups with
     * no bits in {@code conditions} are treated as "don't care" and expanded over all
     * their values.
     */
    private static void addBiome(Map<Integer, List<Short>> builder, short id, int conditions) {
        List<int[]> groupChoices = new ArrayList<>(CONDITION_GROUPS.length);
        for (int[] group : CONDITION_GROUPS) {
            List<Integer> choices = new ArrayList<>();
            for (int bit : group) {
                if ((conditions & bit) != 0) choices.add(bit);
            }
            if (choices.isEmpty()) {
                for (int bit : group) choices.add(bit);
            }
            int[] arr = new int[choices.size()];
            for (int i = 0; i < arr.length; i++) arr[i] = choices.get(i);
            groupChoices.add(arr);
        }
        permute(builder, id, groupChoices, 0, 0);
    }

    private static void permute(Map<Integer, List<Short>> builder, short id,
                                 List<int[]> groupChoices, int groupIdx, int key) {
        if (groupIdx == groupChoices.size()) {
            builder.computeIfAbsent(key, k -> new ArrayList<>()).add(id);
            return;
        }
        for (int bit : groupChoices.get(groupIdx)) {
            permute(builder, id, groupChoices, groupIdx + 1, key | bit);
        }
    }

    static {
        long startTime = System.nanoTime();
        Map<Integer, List<Short>> builder = new HashMap<>();

        // Registration order matters: when multiple biomes share a key, the first registered
        // wins under the current single-result behaviour in classify().


        addBiome(builder, FROZEN_OCEAN, BIT_OCEAN | BIT_FROZEN);
        addBiome(builder, COLD_OCEAN,   BIT_OCEAN | BIT_COLD);
        addBiome(builder, WARM_OCEAN,   BIT_OCEAN | BIT_WARM | BIT_HOT);
        addBiome(builder, OCEAN,        BIT_OCEAN | BIT_COOL | BIT_TEMPERATE);


        addBiome(builder, FROZEN_PEAKS, BIT_MOUNTAIN | BIT_LOWLAND | BIT_MIDLAND | BIT_SLOPE_BARE | BIT_SNOW);
        addBiome(builder, STONY_PEAKS,  BIT_MOUNTAIN | BIT_LOWLAND | BIT_MIDLAND | BIT_SLOPE_BARE | BIT_NO_SNOW);

        addBiome(builder, SNOWY_SLOPES,       BIT_MOUNTAIN | BIT_SNOW | BIT_TREES_NONE);
        addBiome(builder, SNOWY_PLAINS,       BIT_LOWLAND  | BIT_MIDLAND | BIT_SNOW | BIT_TREES_NONE);
        addBiome(builder, SNOWY_TAIGA_SPARSE,  BIT_MOUNTAIN | BIT_LOWLAND | BIT_MIDLAND | BIT_SNOW | BIT_TREES_SPARSE | BIT_TREES_FOREST);
        addBiome(builder, SNOWY_TAIGA,         BIT_MOUNTAIN | BIT_LOWLAND | BIT_MIDLAND | BIT_SNOW | BIT_TREES_DENSE  | BIT_TREES_RAINFOREST);

        addBiome(builder, WINDSWEPT_HILLS, BIT_MOUNTAIN | BIT_NO_SNOW | BIT_TREES_NONE | BIT_BARREN);

        addBiome(builder, DESERT, BIT_LOWLAND | BIT_MIDLAND | BIT_NO_SNOW | BIT_TREES_NONE | BIT_WARM | BIT_HOT);

        addBiome(builder, GROVE,  BIT_MOUNTAIN | BIT_LOWLAND | BIT_MIDLAND | BIT_NO_SNOW | BIT_TREES_NONE | BIT_LOW_MOISTURE);

        addBiome(builder, PLAINS, BIT_MOUNTAIN | BIT_LOWLAND | BIT_MIDLAND | BIT_NO_SNOW | BIT_TREES_NONE | BIT_NOT_LOW_MOISTURE);

        addBiome(builder, JUNGLE,        BIT_LOWLAND | BIT_MIDLAND | BIT_NO_SNOW | BIT_HOT | BIT_TREES_SPARSE | BIT_TREES_FOREST | BIT_TREES_DENSE | BIT_TREES_RAINFOREST);

        addBiome(builder, SAVANNA,       BIT_LOWLAND | BIT_MIDLAND | BIT_NO_SNOW | BIT_WARM | BIT_TREES_SPARSE | BIT_SLOPE_NONE);

        addBiome(builder, FOREST_SPARSE, BIT_LOWLAND | BIT_MIDLAND | BIT_NO_SNOW | BIT_WARM | BIT_TEMPERATE | BIT_TREES_SPARSE | BIT_TREES_FOREST);

        addBiome(builder, SWAMP,         BIT_LOWLAND | BIT_NO_SNOW | BIT_WARM | BIT_TREES_DENSE | BIT_TREES_RAINFOREST);

        addBiome(builder, FOREST,        BIT_LOWLAND | BIT_MIDLAND | BIT_NO_SNOW | BIT_WARM | BIT_TEMPERATE | BIT_TREES_DENSE | BIT_TREES_RAINFOREST);

        addBiome(builder, TAIGA_SPARSE,  BIT_MOUNTAIN | BIT_LOWLAND | BIT_MIDLAND | BIT_NO_SNOW | BIT_TREES_SPARSE | BIT_TREES_FOREST);

        addBiome(builder, TAIGA,         BIT_MOUNTAIN | BIT_LOWLAND | BIT_MIDLAND | BIT_NO_SNOW | BIT_TREES_DENSE  | BIT_TREES_RAINFOREST);

        // Convert List<Short> values to short[] for cache-friendly access
        Map<Integer, short[]> result = new HashMap<>(builder.size() * 2);
        builder.forEach((key, list) -> {
            short[] arr = new short[list.size()];
            for (int i = 0; i < arr.length; i++) arr[i] = list.get(i);
            result.put(key, arr);
        });
        BIOME_MAP = result;
        long elapsedMs = (System.nanoTime() - startTime) / 1_000_000;
        System.out.printf("[Terrain Diffusion] BIOME_MAP built: %d entries in %d ms%n", BIOME_MAP.size(), elapsedMs);
    }

    /**
     * Classify biomes for a grid of pixels.
     *
     * @param elev       elevation in meters, (H, W) row-major
     * @param climate    climate data (5, H, W) row-major or null
     * @param i0         top-left row in world space (for noise sampling)
     * @param j0         top-left col in world space
     * @param elevPadded elevation with 1-pixel padding, (H+2, W+2) row-major
     * @param H          height
     * @param W          width
     * @param pixelSizeM physical size of one pixel in meters
     * @return short array (H, W) with biome IDs
     */
    public static short[] classify(float[] elev, float[] climate, int i0, int j0,
                                    float[] elevPadded, int H, int W, float pixelSizeM) {
        short[] out = new short[H * W];
        for (int i = 0; i < H * W; i++) out[i] = PLAINS;

        if (climate == null || climate.length < 4 * H * W) {
            return out;
        }

        // Generate Perlin noise perturbations
        float[] tempNoise = new float[H * W];
        float[] precipNoiseFact = new float[H * W];
        float[] snowNoise = new float[H * W];

        for (int r = 0; r < H; r++) {
            for (int c = 0; c < W; c++) {
                int idx = r * W + c;
                float nx = j0 + c, ny = i0 + r;
                float tnc = TEMP_NOISE.GetNoise(nx, ny);
                float tnf = TEMP_NOISE_FINE.GetNoise(nx, ny);
                tempNoise[idx] = 0.4f * tnc + 0.2f * tnf;

                float pn = PRECIP_NOISE.GetNoise(nx, ny);
                precipNoiseFact[idx] = 1.0f + 0.2f * pn;

                float snc = SNOW_NOISE.GetNoise(nx, ny);
                float snf = SNOW_NOISE_FINE.GetNoise(nx, ny);
                snowNoise[idx] = 3.0f * snc + 2.0f * snf;
            }
        }

        // Compute slope from padded elevation using Sobel (divide by pixelSizeM for ratio)
        float[] slopeRatio = computeSlopeRatio(elevPadded, H, W, pixelSizeM);

        // Process per-pixel
        for (int r = 0; r < H; r++) {
            for (int c = 0; c < W; c++) {
                int idx = r * W + c;
                float elevVal   = elev[idx];
                float altM      = Math.max(0f, elevVal);
                float slope     = slopeRatio[idx];

                // Climate channels: [0]=temp, [1]=t_season, [2]=precip, [3]=p_cv
                float temp     = climate[idx] + tempNoise[idx];
                float tSeason  = climate[H * W + idx];
                float precip   = Math.max(0f, climate[2 * H * W + idx]) * precipNoiseFact[idx];
                float pCV      = climate[3 * H * W + idx];

                // Derived climate variables
                float tStd     = tSeason / 100f;
                float tEff     = Math.max(0f, temp + 0.5f * tStd);
                float pet      = Math.max(250f, 250f + 25f * tEff + 0.7f * tEff * tEff);
                float aridity  = precip / Math.max(1f, pet);
                float seasonPenalty = 1f - 0.35f * Math.min(1f, pCV / 100f);
                float treeMoisture = aridity * seasonPenalty;

                // Growing season
                float amplitude = tStd * 1.414f;
                float growingSeason;
                if (amplitude < 0.1f) {
                    growingSeason = temp > 5f ? 365f : 0f;
                } else {
                    float x = (5f - temp) / amplitude;
                    if (x <= -1f) growingSeason = 365f;
                    else if (x >= 1f) growingSeason = 0f;
                    else growingSeason = 365f * (0.5f - (float) Math.asin(Math.max(-1f, Math.min(1f, x))) / (float) Math.PI);
                }

                float gsFactor = Math.max(0f, Math.min(1f, (growingSeason - 60f) / (150f - 60f)));
                float effTreeMoisture = treeMoisture * gsFactor;

                // Slope-dependent bare threshold
                float moistureFactor = Math.max(0f, Math.min(1f, (treeMoisture - 0.35f) / 0.45f));
                float bareThreshold = 0.7f + (1.19f - 0.7f) * moistureFactor;

                // Tree coverage classification
                boolean treesNone = effTreeMoisture < 0.2f;
                boolean tooArid   = treeMoisture < 0.05f;
                boolean tooCold   = growingSeason < 60f;
                boolean barren    = tooArid || tooCold;
                boolean treesSparse    = !treesNone && effTreeMoisture < 0.5f;
                boolean treesForest    = !treesNone && effTreeMoisture >= 0.5f && effTreeMoisture < 0.8f;
                boolean treesDense     = !treesNone && effTreeMoisture >= 0.8f && effTreeMoisture < 1.3f;
                boolean treesRainforest = !treesNone && effTreeMoisture >= 1.3f;

                // Slope overrides
                boolean slopeMedium = slope >= 0.62f && slope < bareThreshold;
                boolean slopeBare   = slope >= bareThreshold;
                if (slopeMedium) {
                    if (treesForest || treesDense || treesRainforest) { treesSparse = true; }
                    treesForest = treesForest && false; treesDense = false; treesRainforest = false;
                }
                if (slopeBare) {
                    treesNone = true; treesSparse = false; treesForest = false;
                    treesDense = false; treesRainforest = false;
                }

                // Snow classification
                float snowTemp = temp + snowNoise[idx];
                boolean isSteep = slope > 0.78f;
                boolean hasSnow = snowTemp < 0f && precip > 150f && !isSteep;

                // Elevation/temp bands
                boolean isOcean   = elevVal < 0f;
                boolean mountains = altM > 2500f;
                boolean lowland   = altM < 200f;
                boolean frozen    = temp < -5f;
                boolean cold      = temp >= -5f && temp < 5f;
                boolean cool      = temp >= 5f  && temp < 12f;
                boolean temperate = temp >= 12f && temp < 20f;
                boolean warm      = temp >= 20f && temp < 26f;
                boolean hot       = temp >= 26f;

                // Additional derived conditions
                boolean isLowMoisture = treeMoisture < 0.35f || precip < 350f;
                boolean isLowSeason   = tStd < 5f;

                // Build location condition mask
                int locMask = 0;

                // Terrain (ocean overrides lowland since both can be true when elevVal < 0)
                if (isOcean) locMask |= BIT_OCEAN;
                else if (mountains) locMask |= BIT_MOUNTAIN;
                else if (lowland) locMask |= BIT_LOWLAND;
                else locMask |= BIT_MIDLAND;

                if (frozen) locMask |= BIT_FROZEN;
                else if (cold) locMask |= BIT_COLD;
                else if (cool) locMask |= BIT_COOL;
                else if (temperate) locMask |= BIT_TEMPERATE;
                else if (warm) locMask |= BIT_WARM;
                else locMask |= BIT_HOT;

                if (slopeBare) locMask |= BIT_SLOPE_BARE;
                else if (slopeMedium) locMask |= BIT_SLOPE_MEDIUM;
                else locMask |= BIT_SLOPE_NONE;

                if (treesNone) locMask |= BIT_TREES_NONE;
                else if (treesSparse) locMask |= BIT_TREES_SPARSE;
                else if (treesForest) locMask |= BIT_TREES_FOREST;
                else if (treesDense) locMask |= BIT_TREES_DENSE;
                else locMask |= BIT_TREES_RAINFOREST;

                locMask |= hasSnow       ? BIT_SNOW         : BIT_NO_SNOW;
                locMask |= barren        ? BIT_BARREN        : BIT_NOT_BARREN;
                locMask |= isLowMoisture ? BIT_LOW_MOISTURE  : BIT_NOT_LOW_MOISTURE;
                locMask |= isLowSeason   ? BIT_LOW_SEASON    : BIT_NOT_LOW_SEASON;

                // Look up matching biomes and use the first one
                short[] biomes = BIOME_MAP.get(locMask);
                out[idx] = (biomes != null) ? biomes[0] : PLAINS;
            }
        }
        return out;
    }

    private static float[] computeSlopeRatio(float[] elevPadded, int H, int W, float pixelSizeM) {
        // Sobel kernels / 8 applied to (H+2, W+2) padded array → (H, W) output
        float[] slope = new float[H * W];
        int PW = W + 2;
        float[] sx = {-1,0,1, -2,0,2, -1,0,1};
        float[] sy = {-1,-2,-1, 0,0,0, 1,2,1};
        for (int r = 0; r < H; r++) {
            for (int c = 0; c < W; c++) {
                float dx = 0, dy = 0;
                for (int kr = 0; kr < 3; kr++)
                    for (int kc = 0; kc < 3; kc++) {
                        float v = elevPadded[(r + kr) * PW + (c + kc)];
                        dx += v * sx[kr * 3 + kc];
                        dy += v * sy[kr * 3 + kc];
                    }
                dx /= 8f; dy /= 8f;
                slope[r * W + c] = (float) Math.sqrt(dx * dx + dy * dy) / pixelSizeM;
            }
        }
        return slope;
    }
}
