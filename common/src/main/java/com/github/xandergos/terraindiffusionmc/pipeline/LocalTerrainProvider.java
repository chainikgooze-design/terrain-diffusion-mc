package com.github.xandergos.terraindiffusionmc.pipeline;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.infinitetensor.FloatTensor;
import com.github.xandergos.terraindiffusionmc.world.HeightConverter;
import com.github.xandergos.terraindiffusionmc.world.WorldScaleManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.Comparator;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Provides terrain heightmap and biome data from the local WorldPipeline.
 *
 * <p>When scale=1 the pipeline is sampled at native model resolution directly.
 * When scale>1 the pipeline is sampled at native resolution and the result is
 * bilinearly upsampled, giving 1 block = nativeResolution/scale.
 */
public final class LocalTerrainProvider {
    private static final Logger LOG = LoggerFactory.getLogger(LocalTerrainProvider.class);

    private static final float NATIVE_RESOLUTION = WorldPipelineModelConfig.nativeResolution();

    private static final FastNoiseLite ELEV_NOISE_COARSE = makeFnl(99999, 1f/24f, 3, 2f, 0.5f);
    private static final FastNoiseLite ELEV_NOISE_FINE   = makeFnl(88888, 1f/6f,  2, 2f, 0.6f);

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

    public static final class HeightmapData {
        public final short[][] heightmap;
        public final short[][] biomeIds;
        /** AI temperature in degrees Celsius per terrain column. */
        public final float[][] temperature;
        /** AI precipitation in millimetres per terrain column. */
        public final float[][] precipitation;
        /** AI elevation in (physical) metres per terrain column, matching {@link #heightmap}. */
        public final float[][] elevationMeters;
        public final int width;
        public final int height;

        public HeightmapData(short[][] heightmap, short[][] biomeIds,
                             float[][] temperature, float[][] precipitation,
                             float[][] elevationMeters, int width, int height) {
            this.heightmap       = heightmap;
            this.biomeIds        = biomeIds;
            this.temperature     = temperature;
            this.precipitation   = precipitation;
            this.elevationMeters = elevationMeters;
            this.width           = width;
            this.height          = height;
        }
    }

    private static record CacheKey(int i1, int j1, int i2, int j2) {}
    private static record CacheEntry(HeightmapData data, AtomicLong lastAccessed) {}

    private static final int MAX_CACHE_SIZE = 64;
    private static final int MAX_CACHE_SIZE_HEADROOM = 8;
    private static final Map<CacheKey, CacheEntry> CACHE = new ConcurrentHashMap<>();
    private static final AtomicLong CACHE_CLOCK = new AtomicLong();
    private static final Map<CacheKey, Future<HeightmapData>> PENDING = new ConcurrentHashMap<>();
    /** Single thread for pipeline.get() so MemoryTileStore is not accessed concurrently. */
    private static final ExecutorService INFERENCE_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "terrain-diffusion-inference");
        t.setDaemon(true);
        return t;
    });

    // =========================================================================
    // Coarse (macro) sampling
    // -------------------------------------------------------------------------
    // Single-column height/biome queries (structure placement, getBaseHeight,
    // getFirstFreeHeight, spawn selection, getNoiseBiome) read ONLY the coarse
    // map. They never trigger the base/decoder pipeline, which is strictly
    // reserved for the full-res chunk block generation path (fillArray). This
    // keeps startup responsive: hundreds of one-off macro queries reuse cheap
    // cached coarse tiles instead of each running the full 256-block decode.
    // =========================================================================

    /** Coarse-map macro sample for one block column (no base/decoder inference). */
    public record CoarseSample(float elevationMeters, float temperature, float precipitation) {}

    /** Native pixels per coarse-map pixel (matches WorldPipeline coarse spacing). */
    private static final int COARSE_TO_NATIVE = 32 * WorldPipeline.LATENT_COMPRESSION;

    /** Coarse grid cache keyed by coarse slice bounds (scale-derived, so scale-safe). */
    private static final Map<CacheKey, CoarseGrid> COARSE_CACHE = new ConcurrentHashMap<>();
    private static final Map<CacheKey, Future<CoarseGrid>> COARSE_PENDING = new ConcurrentHashMap<>();

    /** Immutable coarse tile grid extracted from a coarse slice; safe to share across threads. */
    private static final class CoarseGrid {
        final int ci0, cj0;
        final int rows, cols;
        final float[][] elevationMeters;
        final float[][] temperature;
        final float[][] precipitation;

        CoarseGrid(int ci0, int cj0, int rows, int cols,
                   float[][] elevationMeters, float[][] temperature, float[][] precipitation) {
            this.ci0 = ci0;
            this.cj0 = cj0;
            this.rows = rows;
            this.cols = cols;
            this.elevationMeters = elevationMeters;
            this.temperature = temperature;
            this.precipitation = precipitation;
        }
    }

    private static volatile LocalTerrainProvider INSTANCE;
    private static long instanceSeed;

    private final WorldPipeline pipeline;

    private static final Object INIT_LOCK = new Object();

    private LocalTerrainProvider(long seed, PipelineModels models) {
        this.pipeline = new WorldPipeline(seed, models);
    }

    /** Seed is 64-bit world seed. Creates provider once; later worlds only update seed and clear caches (lightweight). */
    public static synchronized void init(long seed) {
        PipelineModels.awaitLoad();
        PipelineModels models = PipelineModels.getInstance();
        if (models == null) throw new IllegalStateException("PipelineModels failed to load");
        if (INSTANCE == null) {
            INSTANCE = new LocalTerrainProvider(seed, models);
            instanceSeed = seed;
        } else if (instanceSeed != seed) {
            INSTANCE.pipeline.setSeed(seed);
            instanceSeed = seed;
            CACHE.clear();
            PENDING.clear();
        }
    }

    public static LocalTerrainProvider getInstance() {
        if (INSTANCE != null) return INSTANCE;

        synchronized(INIT_LOCK) {
            if (INSTANCE != null) return INSTANCE;
            PipelineModels.awaitLoad();
            PipelineModels models = PipelineModels.getInstance();
            if (models == null) throw new IllegalStateException("PipelineModels failed to load");
            INSTANCE = new LocalTerrainProvider(0L, models);
            instanceSeed = 0L;
        }

        return INSTANCE;
    }

    public static void clearCache() {
        CACHE.clear();
        PENDING.clear();
    }

    // =========================================================================
    // Explorer API — all pipeline calls routed through INFERENCE_EXECUTOR
    // =========================================================================

    /** Returns the current world seed used by the pipeline. */
    public static long getSeed() {
        return instanceSeed;
    }

    /**
     * Run elevation and climate inference on the inference thread.
     *
     * @return float[2]: [0] = elev (H*W), [1] = climate (5*H*W, or null)
     */
    public static float[][] getPipelineData(int i1, int j1, int i2, int j2, boolean withClimate) throws Exception {
        return submitToInferenceThread(() -> getInstance().pipeline.get(i1, j1, i2, j2, withClimate));
    }

    /**
     * Fetch a coarse tensor slice on the inference thread.
     * Coordinates are in coarse index units (1 unit = 256 native pixels).
     *
     * @return FloatTensor with shape [7, ci1-ci0, cj1-cj0]
     */
    public static FloatTensor getPipelineCoarse(int ci0, int cj0, int ci1, int cj1) throws Exception {
        return submitToInferenceThread(() -> getInstance().pipeline.getCoarseSlice(ci0, cj0, ci1, cj1));
    }

    /**
     * Change the world seed used by the pipeline and clear all caches.
     * Note: this also affects terrain generation for new Minecraft chunks.
     */
    public static void changeSeedFromExplorer(long newSeed) throws Exception {
        submitToInferenceThread(() -> {
            LocalTerrainProvider provider = getInstance();
            provider.pipeline.setSeed(newSeed);
            instanceSeed = newSeed;
            CACHE.clear();
            PENDING.clear();
            return null;
        });
    }

    /** Change to a random new seed; returns the new seed value. */
    public static long generateRandomSeedFromExplorer() throws Exception {
        long newSeed = new Random().nextLong();
        changeSeedFromExplorer(newSeed);
        return newSeed;
    }

    private static <T> T submitToInferenceThread(Callable<T> task) throws Exception {
        return INFERENCE_EXECUTOR.submit(task).get();
    }

    // =========================================================================
    // Coarse (macro) query API — height/biome for a single block column.
    // These read only the cached coarse map and are cheap; they never start the
    // base/decoder pipeline. Use them for structure placement, spawn selection,
    // getBaseHeight/getFirstFreeHeight, and getNoiseBiome.
    // =========================================================================

    /**
     * Coarse-only block height (Minecraft block Y) for a single column.
     * Bilinearly interpolated from the coarse elevation map. Never runs the full pipeline.
     */
    public static int getCoarseHeight(int blockX, int blockZ) {
        CoarseSample sample = getInstance().sampleCoarse(blockX, blockZ);
        int scale = WorldScaleManager.getCurrentScale();
        return HeightConverter.convertToMinecraftHeight((short) Math.round(sample.elevationMeters()), scale);
    }

    /**
     * Coarse-only climate/elevation sample for a single column (macro query).
     * Returns temperature (C), precipitation (mm) and physical elevation (m),
     * bilinearly interpolated from the coarse map. Never runs the full pipeline.
     */
    public static CoarseSample getCoarseSample(int blockX, int blockZ) {
        return getInstance().sampleCoarse(blockX, blockZ);
    }

    private CoarseSample sampleCoarse(int blockX, int blockZ) {
        CoarseGrid grid = coarseGridForTile(blockX, blockZ);
        int scale = WorldScaleManager.getCurrentScale();
        int blocksPerCoarse = COARSE_TO_NATIVE * scale;

        double fz = (double) blockZ / blocksPerCoarse - Math.floor((double) blockZ / blocksPerCoarse);
        double fx = (double) blockX / blocksPerCoarse - Math.floor((double) blockX / blocksPerCoarse);
        int ci = Math.floorDiv(blockZ, blocksPerCoarse);
        int cj = Math.floorDiv(blockX, blocksPerCoarse);
        int r0 = Math.max(0, Math.min(grid.rows - 2, ci - grid.ci0));
        int c0 = Math.max(0, Math.min(grid.cols - 2, cj - grid.cj0));

        return new CoarseSample(
                bilerp(grid.elevationMeters, r0, c0, fz, fx),
                bilerp(grid.temperature,     r0, c0, fz, fx),
                bilerp(grid.precipitation,   r0, c0, fz, fx));
    }

    private CoarseGrid coarseGridForTile(int blockI, int blockJ) {
        int scale = WorldScaleManager.getCurrentScale();
        int tileSize = TerrainDiffusionConfig.tileSize();
        int shift = Integer.numberOfTrailingZeros(tileSize);
        int i1 = (blockI >> shift) << shift;
        int j1 = (blockJ >> shift) << shift;
        int i2 = i1 + tileSize;
        int j2 = j1 + tileSize;

        int blocksPerCoarse = COARSE_TO_NATIVE * scale;
        // 1-pixel border on each side so bilinear taps are always inside the grid.
        int ci0 = Math.floorDiv(i1, blocksPerCoarse) - 1;
        int cj0 = Math.floorDiv(j1, blocksPerCoarse) - 1;
        int ci1 = -Math.floorDiv(-i2, blocksPerCoarse) + 1;
        int cj1 = -Math.floorDiv(-j2, blocksPerCoarse) + 1;

        CacheKey key = new CacheKey(ci0, cj0, ci1, cj1);
        CoarseGrid cached = COARSE_CACHE.get(key);
        if (cached != null) return cached;

        FutureTask<CoarseGrid> task = new FutureTask<>(() -> {
            FloatTensor slice = pipeline.getCoarseSlice(ci0, cj0, ci1, cj1);
            int rows = ci1 - ci0, cols = cj1 - cj0, n = rows * cols;
            float[][] elev   = new float[rows][cols];
            float[][] temp   = new float[rows][cols];
            float[][] precip = new float[rows][cols];
            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < cols; c++) {
                    int idx = r * cols + c;
                    float w = slice.data[6 * n + idx];
                    if (w <= 1e-6f) {
                        elev[r][c] = 0f; temp[r][c] = 0f; precip[r][c] = 0f;
                        continue;
                    }
                    float v0 = slice.data[idx] / w;              // channel 0: signed-sqrt elev
                    elev[r][c] = Math.signum(v0) * v0 * v0;      // undo signed sqrt -> metres
                    temp[r][c]   = slice.data[2 * n + idx] / w;  // channel 2: temperature
                    precip[r][c] = slice.data[4 * n + idx] / w;  // channel 4: precipitation
                }
            }
            CoarseGrid grid = new CoarseGrid(ci0, cj0, rows, cols, elev, temp, precip);
            COARSE_CACHE.put(key, grid);
            COARSE_PENDING.remove(key);
            return grid;
        });

        Future<CoarseGrid> existing = COARSE_PENDING.putIfAbsent(key, task);
        FutureTask<CoarseGrid> toRun = (existing == null) ? task : (FutureTask<CoarseGrid>) existing;
        if (existing == null) INFERENCE_EXECUTOR.submit(toRun);
        try {
            return toRun.get();
        } catch (Exception e) {
            COARSE_PENDING.remove(key);
            throw new RuntimeException("Coarse sample failed: " + key, e);
        }
    }

    private static float bilerp(float[][] g, int r0, int c0, double fz, double fx) {
        float a = g[r0][c0];
        float b = g[r0][c0 + 1];
        float c = g[r0 + 1][c0];
        float d = g[r0 + 1][c0 + 1];
        return (float) (a * (1 - fz) * (1 - fx)
                + b * (1 - fz) * fx
                + c * fz * (1 - fx)
                + d * fz * fx);
    }

    /**
     * Fetch heightmap for a block-coordinate region (i=Z, j=X).
     * Coordinates are in block space; scale from config determines blocks per native pixel.
     * Blocks the calling thread until the tile is ready (one tile can take 10–30+ seconds).
     * If the caller is the server or a chunk worker, the game will stall until this returns.
     */
    public HeightmapData fetchHeightmap(int i1, int j1, int i2, int j2) {
        CacheKey key = new CacheKey(i1, j1, i2, j2);
        CacheEntry cached = CACHE.get(key);
        if (cached != null) {
            cached.lastAccessed.set(CACHE_CLOCK.incrementAndGet());
            return cached.data;
        }

        return this.genHeightmap(key, i1, j1, i2, j2);
    }

    private HeightmapData genHeightmap(CacheKey key, int i1, int j1, int i2, int j2) {
        int scale = WorldScaleManager.getCurrentScale();
        FutureTask<HeightmapData> task = new FutureTask<>(() -> {
            long computedWindowCountBefore = pipeline.getTotalComputedWindowCount();
            HeightmapData data = scale <= 1
                    ? handle1x(i1, j1, i2, j2)
                    : handleUpsampled(i1, j1, i2, j2, scale);
            long computedWindowCountAfter = pipeline.getTotalComputedWindowCount();

            long newlyComputedWindowCount = computedWindowCountAfter - computedWindowCountBefore;
            int regionWidth = j2 - j1;
            int regionHeight = i2 - i1;
            LOG.info(
                    "Terrain Diffusion ({}) finished generating region {}x{} ({} newly computed windows)",
                    OnnxModel.getResolvedInferenceProvider(), regionWidth, regionHeight, newlyComputedWindowCount);
            CACHE.put(key, new CacheEntry(data, new AtomicLong(CACHE_CLOCK.incrementAndGet())));
            evictLruTo(MAX_CACHE_SIZE);
            PENDING.remove(key);
            return data;
        });
        Future<HeightmapData> existing = PENDING.putIfAbsent(key, task);
        FutureTask<HeightmapData> toRun = (existing == null) ? task : (FutureTask<HeightmapData>) existing;
        if (existing == null) {
            int regionWidth = j2 - j1;
            int regionHeight = i2 - i1;
            LOG.info(
                    "Terrain Diffusion ({}) uncached region requested: ({}, {})-({}, {}) size {}x{}",
                    OnnxModel.getResolvedInferenceProvider(), j1, i1, j2, i2, regionWidth, regionHeight);
            INFERENCE_EXECUTOR.submit(toRun);
        }
        try {
            return toRun.get();
        } catch (Exception e) {
            PENDING.remove(key);
            throw new RuntimeException("Terrain tile failed: " + key, e);
        }
    }

    private static void evictLruTo(int maxSize) {
        int headroomHalf = MAX_CACHE_SIZE_HEADROOM / 2;
        if (CACHE.size() > maxSize + headroomHalf) {
            CACHE.entrySet().stream()
                .sorted(Comparator.comparingLong(e -> e.getValue().lastAccessed.get()))
                .limit(MAX_CACHE_SIZE_HEADROOM)
                .map(Map.Entry::getKey)
                .forEach(CACHE::remove);
        }
    }

    // =========================================================================
    // Scale == 1: block coords == native pixel coords
    // =========================================================================

    private HeightmapData handle1x(int i1, int j1, int i2, int j2) {
        int H = i2 - i1, W = j2 - j1;

        float[] elevPadded = pipeline.get(i1 - 1, j1 - 1, i2 + 1, j2 + 1, false)[0];
        float[][] out = pipeline.get(i1, j1, i2, j2, true);
        float[] elevFlat = out[0];
        float[] climate  = out[1];

        short[] biomeFlat = BiomeClassifier.classify(elevFlat, climate, i1, j1, elevPadded, H, W, NATIVE_RESOLUTION);
        return buildHeightmapData(elevFlat, biomeFlat, climate, H, W);
    }

    // =========================================================================
    // Scale > 1: pipeline at native res → bilinear upsample to block res
    // =========================================================================

    private HeightmapData handleUpsampled(int i1, int j1, int i2, int j2, int scale) {
        int H = i2 - i1, W = j2 - j1;
        float pixelSizeM = NATIVE_RESOLUTION / scale;

        // Convert block coords to native pixel coords
        int i1n = Math.floorDiv(i1, scale);
        int j1n = Math.floorDiv(j1, scale);
        int i2n = -Math.floorDiv(-i2, scale);
        int j2n = -Math.floorDiv(-j2, scale);

        // 2-pixel native padding (1 for bilinear + 1 for slope)
        int i1p = i1n - 2, j1p = j1n - 2;
        int i2p = i2n + 2, j2p = j2n + 2;
        int nH = i2p - i1p, nW = j2p - j1p;

        float[][] out = pipeline.get(i1p, j1p, i2p, j2p, true);
        float[] elevNativeFlat    = out[0];
        float[] climateNativeFlat = out[1];

        // Bilinear upsample elevation: (nH, nW) → (nH*scale, nW*scale)
        float[][] elevNative2D = to2D(elevNativeFlat, nH, nW);
        float[][] elevUp = LaplacianUtils.bilinearResize(elevNative2D, nH * scale, nW * scale);

        // Crop offsets in the upsampled array
        int padUp   = 2 * scale;
        int offsetI = i1 - i1n * scale;
        int offsetJ = j1 - j1n * scale;
        int cropI1  = padUp + offsetI;
        int cropJ1  = padUp + offsetJ;

        float[] elevSmooth = cropFlat(elevUp, cropI1,     cropJ1,     H,   W,   nH * scale, nW * scale);
        float[] elevPadded = cropFlat(elevUp, cropI1 - 1, cropJ1 - 1, H+2, W+2, nH * scale, nW * scale);

        // Upsample climate (4, nH, nW) → (4, H, W)
        float[] climate = upsampleClimate(climateNativeFlat, nH, nW, cropI1, cropJ1, H, W, scale, nH * scale, nW * scale);

        float[] elevOut = addElevationNoise(elevSmooth, elevPadded, i1, j1, H, W, pixelSizeM);

        short[] biomeFlat = BiomeClassifier.classify(elevSmooth, climate, i1, j1, elevPadded, H, W, pixelSizeM);
        return buildHeightmapData(elevOut, biomeFlat, climate, H, W);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private float[] addElevationNoise(float[] elevSmooth, float[] elevPadded,
                                       int i1, int j1, int H, int W, float pixelSizeM) {
        float[] slopeGradient = sobelGradient(elevPadded, H + 2, W + 2, H, W);
        float[] elevOut = elevSmooth.clone();
        float normFactor = 40f * pixelSizeM / NATIVE_RESOLUTION;
        float ampC = 100f * pixelSizeM / NATIVE_RESOLUTION;
        float ampF = 70f  * pixelSizeM / NATIVE_RESOLUTION;

        for (int r = 0; r < H; r++) {
            for (int c = 0; c < W; c++) {
                int idx = r * W + c;
                float e = elevSmooth[idx];
                if (e < 0f) continue;

                float grad = slopeGradient[idx];
                float sf = Math.min(1f, grad / normFactor);
                sf = sf * sf * (float) Math.sqrt(sf);

                float nx = j1 + c, ny = i1 + r;
                elevOut[idx] = e
                        + ELEV_NOISE_COARSE.GetNoise(nx, ny) * ampC * sf
                        + ELEV_NOISE_FINE.GetNoise(nx, ny)   * ampF * sf;
            }
        }
        return elevOut;
    }

    private static float[] sobelGradient(float[] padded, int pH, int pW, int H, int W) {
        final float[] SOBEL_X = {-1,0,1, -2,0,2, -1,0,1};
        final float[] SOBEL_Y = {-1,-2,-1, 0,0,0, 1,2,1};
        float[] result = new float[H * W];
        for (int r = 0; r < H; r++) {
            for (int c = 0; c < W; c++) {
                float dx = 0, dy = 0;
                for (int k = 0; k < 9; k++) {
                    float v = padded[(r + k/3) * pW + (c + k%3)];
                    dx += v * SOBEL_X[k];
                    dy += v * SOBEL_Y[k];
                }
                dx /= 8f; dy /= 8f;
                result[r * W + c] = (float) Math.sqrt(dx * dx + dy * dy);
            }
        }
        return result;
    }

    private static float[] upsampleClimate(float[] climNative, int nH, int nW,
                                            int cropI1, int cropJ1, int H, int W,
                                            int scale, int upH, int upW) {
        if (climNative == null) return null;
        float[] result = new float[4 * H * W];
        for (int ch = 0; ch < 4; ch++) {
            float[][] chNative = new float[nH][nW];
            for (int r = 0; r < nH; r++)
                System.arraycopy(climNative, ch * nH * nW + r * nW, chNative[r], 0, nW);
            float[][] chUp = LaplacianUtils.bilinearResize(chNative, upH, upW);
            for (int r = 0; r < H; r++)
                for (int c = 0; c < W; c++)
                    result[ch * H * W + r * W + c] = chUp[cropI1 + r][cropJ1 + c];
        }
        return result;
    }

    private static float[] cropFlat(float[][] src, int r0, int c0, int H, int W, int srcH, int srcW) {
        float[] out = new float[H * W];
        for (int r = 0; r < H; r++) {
            int sr = Math.max(0, Math.min(srcH - 1, r0 + r));
            for (int c = 0; c < W; c++)
                out[r * W + c] = src[sr][Math.max(0, Math.min(srcW - 1, c0 + c))];
        }
        return out;
    }

    private static float[][] to2D(float[] flat, int H, int W) {
        float[][] a = new float[H][W];
        for (int r = 0; r < H; r++) System.arraycopy(flat, r * W, a[r], 0, W);
        return a;
    }

    private static HeightmapData buildHeightmapData(float[] elevFlat, short[] biomeFlat, float[] climate, int H, int W) {
        short[][] heightmap = new short[H][W];
        short[][] biomeIds  = new short[H][W];
        float[][] elevationMeters = new float[H][W];
        float[][] temperature     = new float[H][W];
        float[][] precipitation   = new float[H][W];

        boolean hasClimate = climate != null && climate.length >= 3 * H * W;
        for (int r = 0; r < H; r++)
            for (int c = 0; c < W; c++) {
                int idx = r * W + c;
                float e = elevFlat[idx];
                heightmap[r][c] = (short) Math.max(-32768, Math.min(32767, (int) Math.floor(e)));
                biomeIds[r][c]  = biomeFlat == null ? 0 : biomeFlat[idx];
                elevationMeters[r][c] = e;
                if (hasClimate) {
                    // Climate layout: channel 0 = temperature (C), channel 2 = precipitation (mm).
                    temperature[r][c]   = climate[idx];
                    precipitation[r][c] = climate[2 * H * W + idx];
                }
            }
        // Legacy (explorer/back-compat) biome ids are kept; world-gen biomes are
        // now resolved through the vanilla MultiNoiseBiomeSource (see AIToVanillaClimateSampler).
        return new HeightmapData(heightmap, biomeIds, temperature, precipitation, elevationMeters, W, H);
    }
}
