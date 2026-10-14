package com.github.xandergos.terraindiffusionmc.world;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
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
     * Reads the cached AI climate at the given block coordinates and returns the mapped
     * vanilla climate parameter for {@code axis} in [-1, 1].
     *
     * <p>MACRO-QUERY: this feeds {@code getNoiseBiome}. It is sampled block-by-block by the
     * MultiNoiseBiomeSource, so it reads the cheap cached coarse map (bilinear interpolation)
     * and NEVER triggers the full base+decoder pipeline.
     */
    private double sampleAxis(int blockX, int blockZ, Axis axis) {
        int tileSize = TerrainDiffusionConfig.tileSize();
        int tileShift = Integer.numberOfTrailingZeros(tileSize);
        int blockStartX = (blockX >> tileShift) << tileShift;
        int blockStartZ = (blockZ >> tileShift) << tileShift;
        int blockEndX = blockStartX + tileSize;
        int blockEndZ = blockStartZ + tileSize;

        LocalTerrainProvider.HeightmapData data = LocalTerrainProvider.getInstance()
                .getIfCached(blockStartZ, blockStartX, blockEndZ, blockEndX);

        float temp, precip, elev;

        if (data != null && data.temperature != null && data.precipitation != null && data.elevationMeters != null) {
            int localX = Math.max(0, Math.min(data.width - 1, blockX - blockStartX));
            int localZ = Math.max(0, Math.min(data.height - 1, blockZ - blockStartZ));
            temp = data.temperature[localZ][localX];
            precip = data.precipitation[localZ][localX];
            elev = data.elevationMeters[localZ][localX];
        } else {
            LocalTerrainProvider.CoarseSample sample = LocalTerrainProvider.getCoarseSample(blockX, blockZ);
            temp = sample.temperature();
            precip = sample.precipitation();
            elev = sample.elevationMeters();
        }

        switch (axis) {
            case TEMPERATURE: return remap(temp, TEMP_C, TEMP_PARAM);
            case HUMIDITY: return remap(precip, PRECIP_MM, HUMIDITY_PARAM);
            case CONTINENTALNESS: return remap(elev, ELEV_M, CONTINENT_PARAM);
            default: return 0.0;
        }
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
