package com.github.xandergos.terraindiffusionmc.pipeline;

import com.github.xandergos.terraindiffusionmc.mixin.BiomeAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.biome.Biome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    private static final Logger LOG = LoggerFactory.getLogger(BiomeClassifier.class);
    // Fixed-seed noise instances (matching Python's module-level _TEMP_NOISE etc.)
    private static final FastNoiseLite TEMP_NOISE, TEMP_NOISE_FINE;
    private static final FastNoiseLite PRECIP_NOISE;
    private static final FastNoiseLite SNOW_NOISE, SNOW_NOISE_FINE;
    private static final FastNoiseLite BIOME_NOISE, BIOME_NOISE_WARP;

    static {
        TEMP_NOISE = makeFnlPerlin(12345, 1f/500f, 3, 2f, 0.5f);
        TEMP_NOISE_FINE = makeFnlPerlin(54321, 1f/128f, 2, 2f, 0.5f);
        PRECIP_NOISE = makeFnlPerlin(12345, 1f/500f, 5, 2f, 0.5f);
        SNOW_NOISE = makeFnlPerlin(12345, 1f/500f, 3, 2f, 0.5f);
        SNOW_NOISE_FINE = makeFnlPerlin(54321, 1f/128f, 2, 2f, 0.5f);
        BIOME_NOISE = makeFnlCell(12345, 1f/1000f);
        BIOME_NOISE_WARP = makeFnlWarp(12345, 1f/100f, 115f, 2, 2.0f, 0.54f);
    }

    //maybe look into using fastnoise2 instead.
    private static FastNoiseLite makeFnlPerlin(int seed, float freq, int oct, float lac, float gain) {
        FastNoiseLite fnl = new FastNoiseLite(seed);
        fnl.SetNoiseType(FastNoiseLite.NoiseType.Perlin);
        fnl.SetFrequency(freq);
        fnl.SetFractalType(FastNoiseLite.FractalType.FBm);
        fnl.SetFractalOctaves(oct);
        fnl.SetFractalLacunarity(lac);
        fnl.SetFractalGain(gain);
        return fnl;
    }

    private static FastNoiseLite makeFnlCell(int seed, float freq) {
        FastNoiseLite fnl = new FastNoiseLite(seed);
        fnl.SetNoiseType(FastNoiseLite.NoiseType.Cellular);
        fnl.SetFrequency(freq);
        fnl.SetCellularReturnType(FastNoiseLite.CellularReturnType.CellValue);
        return fnl;
    }

    private static FastNoiseLite makeFnlWarp(int seed, float freq, float amp, int oct, float lac, float gain) {
        FastNoiseLite fnl = new FastNoiseLite(seed);
        fnl.SetDomainWarpType(FastNoiseLite.DomainWarpType.OpenSimplex2);
        fnl.SetFrequency(freq);
        fnl.SetDomainWarpAmp(amp);
        fnl.SetFractalType(FastNoiseLite.FractalType.DomainWarpProgressive);
        fnl.SetFractalOctaves(oct);
        fnl.SetFractalLacunarity(lac);
        fnl.SetFractalGain(gain);
        return fnl;
    }

    private enum elevation {
        DEEP_OCEAN,
        OCEAN,
        LOWLAND,
        MIDLAND,
        HIGHLAND,
        MOUNTAIN
    }

    private enum temperature {
        FROZEN,
        COLD,
        COOL,
        TEMPERATE,
        WARM,
        HOT
    }

    private enum slope {
        NONE,
        MEDIUM,
        BARE
    }

    private enum treeCoverage {
        BARREN,
        NONE,
        SPARSE,
        FOREST,
        DENSE,
        RAINFOREST
    }

    //borrowed from ThatDamnWittyWhizHard's PR
    private enum moisture {
        VERY_DRY,
        DRY,
        SEMI_DRY,
        MOIST,
        VERY_MOIST,
        SATURATED
    }


    //records are automatically given hash functions which makes it easier to use with HashMap.
    record climate(
            elevation elev,
            temperature temp,
            slope slope,
            treeCoverage treecover,
            moisture moist,
            boolean snow
    ){};


    // Maps a fully-specified location condition to the biome IDs valid there.
    // Populated dynamically by {@link #initializeDynamic(HolderLookup.Provider)}.
    private static volatile HashMap<climate, short[]> BIOME_MAP = new HashMap<>();

    // Dynamic ID allocation: bidirectional Identifier <-> short mapping for downstream consumers.
    private static final HashMap<Short, ResourceLocation> ID_TO_BIOME = new HashMap<>();
    private static final HashMap<ResourceLocation, Short> BIOME_TO_ID = new HashMap<>();
    private static short nextBiomeId = 1; // 0 reserved as "unassigned" / fallback sentinel
    private static short fallbackBiomeId = 0;
    private static volatile boolean initialized = false;

    /**
     * Registers a biome for all condition combinations described by the condition arrays given.
     *
     * <p>For each condition group, the conditions present form an OR:
     * the biome is registered for every combination. Null arrays are
     * treated as "don't care" and expanded over all reasonable values.
     */
    private static void addBiome(Map<climate, List<Short>> builder, short id,
                                 elevation[] elevations,
                                 temperature[] temperatures,
                                 slope[] slopes,
                                 treeCoverage[] treeCoverages,
                                 moisture[] moistures,
                                 boolean[] hasSnow) {

        //if any of the inputs are null, we consider that a "dont care" and fill in every combination.
        if (elevations == null) elevations = new elevation[]{elevation.LOWLAND, elevation.MIDLAND, elevation.HIGHLAND, elevation.MOUNTAIN};
        if (temperatures == null) temperatures = temperature.values();
        if (slopes == null) slopes = new slope[]{slope.MEDIUM, slope.NONE};
        if (treeCoverages == null) treeCoverages = treeCoverage.values();
        if (moistures == null) moistures = moisture.values();
        if (hasSnow == null) hasSnow = new boolean[] {false, true}; //this seems weird but trust me it makes sense i think

        //blech
        for (elevation elev : elevations)
            for (temperature temp : temperatures)
                for (slope slope : slopes)
                    for (treeCoverage trees : treeCoverages)
                        for (moisture moist : moistures)
                            for (boolean snow : hasSnow)
                            {
                                climate c = new climate(elev, temp, slope, trees, moist, snow);
                                builder.computeIfAbsent(c, k -> new ArrayList<>()).add(id);
                            }

    }


    // The previously hardcoded BIOME_MAP static initializer has been removed.
    // Biomes are now discovered dynamically from the live registry at server start.
    // See initializeDynamic(HolderLookup.Provider) below.

    /**
     * Dynamically scans the biome registry and registers every biome (vanilla + modded)
     * into BIOME_MAP based on its Minecraft-side properties.
     *
     * <p>Must be called once after the registries are loaded (e.g. during server start)
     * before classify() is invoked. Safe to call multiple times; the map is rebuilt each time.
     */
    public static synchronized void initializeDynamic(HolderLookup.Provider registries) {
        long startTime = System.nanoTime();
        Map<climate, List<Short>> builder = new HashMap<>(1024);
        ID_TO_BIOME.clear();
        BIOME_TO_ID.clear();
        nextBiomeId = 1;

        HolderLookup.RegistryLookup<Biome> biomeRegistry = registries.lookupOrThrow(Registries.BIOME);
        BlockPos samplePos = BlockPos.ZERO;

        biomeRegistry.listElements().forEach(entry -> registerBiomeEntry(builder, entry, samplePos));

        // Establish fallback (prefer vanilla ocean, else first registered biome)
        Short oceanId = BIOME_TO_ID.get(ResourceLocation.withDefaultNamespace("ocean"));
        fallbackBiomeId = oceanId != null ? oceanId : (short) (nextBiomeId > 1 ? 1 : 0);

        // Convert List<Short> values to short[] for cache-friendly access
        HashMap<climate, short[]> result = new HashMap<>(builder.size() * 2);
        builder.forEach((key, list) -> {
            short[] arr = new short[list.size()];
            for (int i = 0; i < arr.length; i++) arr[i] = list.get(i);
            result.put(key, arr);
        });
        BIOME_MAP = result;
        initialized = true;
        long elapsedMs = (System.nanoTime() - startTime) / 1_000_000;
        LOG.info("BIOME_MAP built dynamically: {} biomes registered, {} climate entries in {} ms",
                ID_TO_BIOME.size(), BIOME_MAP.size(), elapsedMs);
    }

    /**
     * Classifies a single biome registry entry into the rule table by examining its
     * Minecraft properties (temperature, downfall) and tags (IS_MOUNTAIN, IS_OCEAN, etc.).
     */
    private static void registerBiomeEntry(Map<climate, List<Short>> builder,
                                           Holder.Reference<Biome> entry,
                                           BlockPos samplePos) {
        ResourceLocation id = entry.key().location();
        Biome biome = entry.value();

        // Allocate a short id and remember the mapping
        short shortId = nextBiomeId++;
        ID_TO_BIOME.put(shortId, id);
        BIOME_TO_ID.put(id, shortId);

        // --- Temperature mapping (vanilla baseTemperature -> our 6 buckets) ---
        // Vanilla: <0 frozen (icy), 0-0.2 cold, 0.2-0.5 cool/temperate, 0.5-0.8 warm, >=0.8 hot.
        float t = biome.getBaseTemperature();
        temperature[] temps;
        if (t < 0.0f)       temps = new temperature[]{temperature.FROZEN};
        else if (t < 0.2f)  temps = new temperature[]{temperature.COLD};
        else if (t < 0.5f)  temps = new temperature[]{temperature.COOL, temperature.TEMPERATE};
        else if (t < 0.8f)  temps = new temperature[]{temperature.WARM};
        else                temps = new temperature[]{temperature.HOT};

        // --- Moisture / downfall mapping via accessor mixin ---
        float downfall;
        try {
            Biome.ClimateSettings cs = ((BiomeAccessor) (Object) biome).terrainDiffusion$getClimateSettings();
            downfall = cs != null ? cs.downfall() : 0.4f;
        } catch (Throwable th) {
            // Defensive: if the accessor mixin failed to apply, fall back to a neutral value
            downfall = biome.hasPrecipitation() ? 0.5f : 0.1f;
        }

        moisture[] moists;
        if (downfall < 0.15f)       moists = new moisture[]{moisture.VERY_DRY, moisture.DRY};
        else if (downfall < 0.30f)  moists = new moisture[]{moisture.DRY, moisture.SEMI_DRY};
        else if (downfall < 0.55f)  moists = new moisture[]{moisture.SEMI_DRY, moisture.MOIST};
        else if (downfall < 0.80f)  moists = new moisture[]{moisture.MOIST, moisture.VERY_MOIST};
        else                        moists = new moisture[]{moisture.VERY_MOIST, moisture.SATURATED};

        // --- Tag-based elevation / slope inference ---
        boolean isMountain = entry.is(BiomeTags.IS_MOUNTAIN) || entry.is(BiomeTags.IS_HILL);
        boolean isOcean    = entry.is(BiomeTags.IS_OCEAN) || entry.is(BiomeTags.IS_DEEP_OCEAN);
        boolean isDeep     = entry.is(BiomeTags.IS_DEEP_OCEAN);
        boolean isForest   = entry.is(BiomeTags.IS_FOREST);
        boolean isJungle   = entry.is(BiomeTags.IS_JUNGLE);
        boolean isTaiga    = entry.is(BiomeTags.IS_TAIGA);
        boolean isSavanna  = entry.is(BiomeTags.IS_SAVANNA);
        boolean isBadlands = entry.is(BiomeTags.IS_BADLANDS);

        elevation[] elevs;
        slope[] slopes;
        if (isDeep) {
            elevs  = new elevation[]{elevation.DEEP_OCEAN};
            slopes = new slope[]{slope.NONE};
        } else if (isOcean) {
            elevs  = new elevation[]{elevation.OCEAN};
            slopes = new slope[]{slope.NONE};
        } else if (isMountain) {
            elevs  = new elevation[]{elevation.HIGHLAND, elevation.MOUNTAIN};
            slopes = new slope[]{slope.MEDIUM, slope.BARE};
        } else {
            elevs  = new elevation[]{elevation.LOWLAND, elevation.MIDLAND};
            slopes = new slope[]{slope.NONE, slope.MEDIUM};
        }

        // --- Tree coverage inference from tags ---
        treeCoverage[] cover;
        if (isJungle)         cover = new treeCoverage[]{treeCoverage.DENSE, treeCoverage.RAINFOREST};
        else if (isForest)    cover = new treeCoverage[]{treeCoverage.FOREST, treeCoverage.DENSE};
        else if (isTaiga)     cover = new treeCoverage[]{treeCoverage.FOREST, treeCoverage.DENSE};
        else if (isSavanna)   cover = new treeCoverage[]{treeCoverage.SPARSE};
        else if (isBadlands)  cover = new treeCoverage[]{treeCoverage.BARREN, treeCoverage.NONE};
        else                  cover = null; // "don't care"

        // --- Snow flag ---
        // 1.21.1: Biome#coldEnoughToSnow(BlockPos) is the public guard used by precipitation logic.
        boolean canSnow = biome.coldEnoughToSnow(samplePos);
        boolean[] snow = canSnow ? new boolean[]{true} : new boolean[]{false};

        // --- Modded namespace heuristics (e.g. "meadow", "swamp", "dune") ---
        String ns = id.getNamespace();
        if (!"minecraft".equals(ns)) {
            String path = id.getPath();
            if (path.contains("meadow") || path.contains("plain") || path.contains("field")) {
                if (cover == null) cover = new treeCoverage[]{treeCoverage.NONE, treeCoverage.SPARSE};
            } else if (path.contains("desert") || path.contains("dune")) {
                cover = new treeCoverage[]{treeCoverage.BARREN, treeCoverage.NONE};
            } else if (path.contains("swamp") || path.contains("marsh") || path.contains("bog")) {
                cover = new treeCoverage[]{treeCoverage.DENSE, treeCoverage.RAINFOREST};
                if (moists[0].ordinal() < moisture.MOIST.ordinal()) {
                    moists = new moisture[]{moisture.VERY_MOIST, moisture.SATURATED};
                }
            }
            LOG.debug("Registered modded biome {} (id={}) t={} downfall={}", id, shortId, t, downfall);
        }

        // Expand into the climate-tuple rule table via the existing combinatorial helper.
        addBiome(builder, shortId, elevs, temps, slopes, cover, moists, snow);
    }

    /** Lookup the Minecraft ResourceLocation for a classifier short id, or null if unknown. */
    public static ResourceLocation getBiomeId(short id) {
        return ID_TO_BIOME.get(id);
    }

    /** Lookup the classifier short id for a Minecraft ResourceLocation, or the fallback id. */
    public static short getShortId(ResourceLocation id) {
        return BIOME_TO_ID.getOrDefault(id, fallbackBiomeId);
    }

    /** True once {@link #initializeDynamic(HolderLookup.Provider)} has populated BIOME_MAP. */
    public static boolean isInitialized() {
        return initialized;
    }

    /**
     * Classify biomes for a grid of pixels.
     *
     * <p>Requires {@link #initializeDynamic(HolderLookup.Provider)} to have been called;
     * otherwise the BIOME_MAP is empty and every pixel falls through to the fallback id.
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
        final short fallback = fallbackBiomeId;
        for (int i = 0; i < H * W; i++) out[i] = fallback;

        if (climate == null || climate.length < 4 * H * W) {
            return out;
        }

        // Generate Perlin noise perturbations
        float[] tempNoise = new float[H * W];
        float[] precipNoiseFact = new float[H * W];
        float[] snowNoise = new float[H * W];
        float[] biomeNoise = new float[H * W];

        //im not sure it matters much to define it out here, probably only lowers the allocation rate by like 1kb lmao
        FastNoiseLite.Vector2 warpvector = new FastNoiseLite.Vector2(0,0);

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

                warpvector.x = nx; warpvector.y = ny; //mfw this is the only thing that fastnoiselite uses vectors for wtf
                BIOME_NOISE_WARP.DomainWarp(warpvector);
                biomeNoise[idx] = (BIOME_NOISE.GetNoise(warpvector.x, warpvector.y)+1f)/2f;
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

                elevation Elev;

                if (isOcean)        Elev = elevation.OCEAN;
                else if (mountains) Elev = elevation.MOUNTAIN;
                else if (lowland)   Elev = elevation.LOWLAND;
                else                Elev = elevation.MIDLAND;

                temperature Temp;

                if (frozen)         Temp = temperature.FROZEN;
                else if (cold)      Temp = temperature.COLD;
                else if (cool)      Temp = temperature.COOL;
                else if (temperate) Temp = temperature.TEMPERATE;
                else if (warm)      Temp = temperature.WARM;
                else                Temp = temperature.HOT;

                BiomeClassifier.slope Slope;

                if (slopeBare)          Slope = BiomeClassifier.slope.BARE;
                else if (slopeMedium)   Slope = BiomeClassifier.slope.MEDIUM;
                else                    Slope = BiomeClassifier.slope.NONE;

                treeCoverage Cover;

                if (barren) Cover =             treeCoverage.BARREN;
                else if (treesNone) Cover =     treeCoverage.NONE;
                else if (treesSparse) Cover =   treeCoverage.SPARSE;
                else if (treesForest) Cover =   treeCoverage.FOREST;
                else if (treesDense) Cover =    treeCoverage.DENSE;
                else Cover =                    treeCoverage.RAINFOREST;

                moisture Moisture;

                if (treeMoisture < 0.12f || precip < 180f)          Moisture = moisture.VERY_DRY;
                else if (treeMoisture < 0.35f || precip < 350f)     Moisture = moisture.DRY;
                else if (treeMoisture < 0.55f || precip < 520f)     Moisture = moisture.SEMI_DRY;
                else if (treeMoisture >= 1.45f || precip > 1650f)   Moisture = moisture.SATURATED;
                else if (treeMoisture >= 1.15f || precip > 1250f)   Moisture = moisture.VERY_MOIST;
                else                                                Moisture = moisture.MOIST;

                                                        //probably an insane memory leak
                BiomeClassifier.climate climateResult = new climate(Elev, Temp, Slope, Cover, Moisture, hasSnow);


                // Look up matching biomes and randomize based on cell noise.
                // Multiple biomes (vanilla + modded) sharing the same climate tuple are
                // distributed via the warped Voronoi/cell noise, preserving the original behavior.
                short[] biomes = BIOME_MAP.get(climateResult);
                if (biomes == null || biomes.length == 0) {
                    // No matching biome registered for this climate tuple.
                    // Use the fallback (vanilla ocean if available) — clearly visible so
                    // missing combinations can be diagnosed rather than silently masked.
                    out[idx] = fallback;
                }
                else if (biomes.length == 1) {
                    out[idx] = biomes[0];
                }
                else {
                    out[idx] = biomes[ Math.min((int)(biomeNoise[idx]*biomes.length), biomes.length - 1) ];
                }
            }
        }
        return out;
    }

    private static float[] computeSlopeRatio(float[] elevPadded, int H, int W, float pixelSizeM) {
        // Sobel kernels / 8 applied to (H+2, W+2) paddedS array → (H, W) output
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
