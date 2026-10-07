package com.github.xandergos.terraindiffusionmc.world;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider.HeightmapData;
import com.mojang.serialization.Codec;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;

import java.util.List;

/**
 * Bridges the AI terrain outputs (elevation / temperature / precipitation) into the
 * six vanilla climate parameters consumed by the vanilla
 * {@link net.minecraft.world.level.biome.MultiNoiseBiomeSource}.
 *
 * <p>In Minecraft 1.21.1 {@link Climate.Sampler} is a record holding six
 * {@link DensityFunction}s plus a spawn-target list, so this class no longer implements
 * the sampler directly. Instead it exposes {@link #buildSampler()} which constructs a
 * {@link Climate.Sampler} whose density functions read the cached AI arrays (temperature,
 * humidity, continentalness) and return values in [-1, 1]. Erosion, depth and weirdness are
 * left neutral (0). Because the pixels are still resolved through the vanilla MultiNoise
 * machinery, datapack biomes and NEOFORGE biome modifier geologies (ores, vegetation) work
 * natively.
 *
 * <p><b>Performance contract:</b> the density functions are sampled block-by-block by the
 * MultiNoiseBiomeSource and must NEVER trigger blocking AI inference. AI arrays are cached
 * per tile by {@link LocalTerrainProvider}; the functions only read from that cache and
 * keep a small reference to the last tile they touched.
 */
public final class AIToVanillaClimateSampler {
    // ------------------------------------------------------------------
    // Mapping anchors: AI value -> vanilla parameter in [-1.0, 1.0].
    // Keep everything strictly inside [-1, 1] to avoid void generation if a
    // sampled value ever runs out of the MultiNoise parameter bounds.
    // ------------------------------------------------------------------

    /** AI temperature (Celsius) -> vanilla temperature parameter. */
    private static final float[] TEMP_C    = {-20f, -10f, 0f, 10f, 15f, 20f, 26f, 35f, 45f};
    private static final float[] TEMP_PARAM = {-1f, -0.7f, -0.4f, -0.1f, 0.1f, 0.3f, 0.5f, 0.9f, 1f};

    /** AI precipitation (mm) -> vanilla humidity parameter. */
    private static final float[] PRECIP_MM     = {0f, 150f, 400f, 700f, 1100f, 1600f, 2200f, 3000f};
    private static final float[] HUMIDITY_PARAM = {-1f, -0.6f, -0.2f, 0.1f, 0.4f, 0.7f, 0.9f, 1f};

    /** AI elevation (physical metres) -> vanilla continentalness parameter. */
    private static final float[] ELEV_M         = {-1000f, -250f, -50f, 0f, 300f, 1500f, 4000f, 9000f};
    private static final float[] CONTINENT_PARAM = {-1f, -0.6f, -0.3f, -0.2f, 0.1f, 0.4f, 0.8f, 0.95f};

    // Cached last tile so repeated per-column sampling does not re-lookup the cache.
    private int cachedTileX = Integer.MIN_VALUE;
    private int cachedTileZ = Integer.MIN_VALUE;
    private HeightmapData cachedData;

    /**
     * Builds the vanilla {@link Climate.Sampler} that feeds the AI climate into the
     * MultiNoiseBiomeSource. The returned record holds the six density functions consumed
     * by {@link Climate.Sampler#sample(int, int, int)}.
     */
    public Climate.Sampler buildSampler() {
        return new Climate.Sampler(
                aiFunction(Axis.TEMPERATURE),
                aiFunction(Axis.HUMIDITY),
                aiFunction(Axis.CONTINENTALNESS),
                DensityFunctions.zero(), // erosion: neutral
                DensityFunctions.zero(), // depth: neutral
                DensityFunctions.zero(), // weirdness: neutral
                List.of() // no spawn target; findSpawnPosition() returns BlockPos.ZERO
        );
    }

    private DensityFunction aiFunction(Axis axis) {
        return new AiClimateFunction(axis);
    }

    /**
     * Reads the cached AI tile at the given block coordinates and returns the mapped
     * vanilla climate parameter for {@code axis} in [-1, 1].
     */
    private double sampleAxis(int blockX, int blockZ, Axis axis) {
        HeightmapData data = fetchTile(blockX, blockZ);
        if (data == null || data.temperature == null) {
            // No cached AI data yet; return a safe mid/neutral value so we never fall
            // into a void (MultiNoise always selects *some* viable biome).
            return 0.0;
        }

        int localX = Math.max(0, Math.min(data.width - 1, blockX - tileStartX(blockX)));
        int localZ = Math.max(0, Math.min(data.height - 1, blockZ - tileStartZ(blockZ)));

        switch (axis) {
            case TEMPERATURE:
                return remap(data.temperature[localZ][localX], TEMP_C, TEMP_PARAM);
            case HUMIDITY:
                return remap(data.precipitation[localZ][localX], PRECIP_MM, HUMIDITY_PARAM);
            case CONTINENTALNESS:
                return remap(data.elevationMeters[localZ][localX], ELEV_M, CONTINENT_PARAM);
            default:
                return 0.0;
        }
    }

    // ------------------------------------------------------------------
    // Tile lookup
    // ------------------------------------------------------------------

    private HeightmapData fetchTile(int blockX, int blockZ) {
        int tileX = tileStartX(blockX);
        int tileZ = tileStartZ(blockZ);
        HeightmapData data = cachedData;
        if (data == null || cachedTileX != tileX || cachedTileZ != tileZ) {
            int tileSize = TerrainDiffusionConfig.tileSize();
            data = LocalTerrainProvider.getInstance()
                    .fetchHeightmap(tileZ, tileX, tileZ + tileSize, tileX + tileSize);
            cachedTileX = tileX;
            cachedTileZ = tileZ;
            cachedData = data;
        }
        return data;
    }

    private static int tileStartX(int blockX) {
        int tileSize = TerrainDiffusionConfig.tileSize();
        int shift = Integer.numberOfTrailingZeros(tileSize);
        return (blockX >> shift) << shift;
    }

    private static int tileStartZ(int blockZ) {
        return tileStartX(blockZ); // same computation for the Z axis
    }

    // ------------------------------------------------------------------
    // Mapping helper
    // ------------------------------------------------------------------

    /** Piecewise-linear remap of {@code value} to the range spanned by {@code ys}. */
    private static float remap(float value, float[] xs, float[] ys) {
        if (value <= xs[0]) return ys[0];
        for (int i = 1; i < xs.length; i++) {
            if (value < xs[i]) {
                float t = (value - xs[i - 1]) / (xs[i] - xs[i - 1]);
                return ys[i - 1] + t * (ys[i] - ys[i - 1]);
            }
        }
        return ys[ys.length - 1];
    }

    // ------------------------------------------------------------------
    // AI axis selector
    // ------------------------------------------------------------------

    private enum Axis {
        TEMPERATURE, HUMIDITY, CONTINENTALNESS
    }

    /**
     * A runtime {@link DensityFunction} that samples one AI climate axis. Because these
     * functions are constructed at runtime and never round-trip through NBT, their codec is
     * a dummy unit codec.
     */
    private final class AiClimateFunction implements DensityFunction {
        private final Axis axis;

        AiClimateFunction(Axis axis) {
            this.axis = axis;
        }

        @Override
        public double compute(DensityFunction.FunctionContext context) {
            return AIToVanillaClimateSampler.this.sampleAxis(context.blockX(), context.blockZ(), axis);
        }

        @Override
        public void fillArray(double[] array, DensityFunction.ContextProvider provider) {
            for (int i = 0; i < array.length; i++) {
                array[i] = this.compute(provider.forIndex(i));
            }
        }

        @Override
        public DensityFunction mapAll(DensityFunction.Visitor visitor) {
            return this;
        }

        @Override
        public double minValue() {
            return -1.0;
        }

        @Override
        public double maxValue() {
            return 1.0;
        }

        @Override
        public KeyDispatchDataCodec<? extends DensityFunction> codec() {
            return KeyDispatchDataCodec.of(Codec.unit(this).fieldOf("dummy"));
        }
    }
}
