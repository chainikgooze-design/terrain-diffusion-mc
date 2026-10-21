package com.github.xandergos.terraindiffusionmc.world;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.pipeline.FastNoiseLite;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.levelgen.DensityFunction;

import java.util.Locale;

/**
 * Data-driven {@link DensityFunction} that samples AI terrain outputs (temperature,
 * precipitation, elevation) or procedural erosion/weirdness noise and maps them to [-1.0, 1.0].
 *
 * <p>Can be referenced in {@code noise_settings} JSONs via {@code {"type": "terrain-diffusion-mc:climate", "axis": "temperature"}}.
 */
public final class TerrainDiffusionClimateFunction implements DensityFunction {

    public enum ClimateAxis implements StringRepresentable {
        TEMPERATURE("temperature"),
        HUMIDITY("humidity"),
        CONTINENTALNESS("continentalness"),
        EROSION("erosion"),
        WEIRDNESS("weirdness");

        public static final Codec<ClimateAxis> CODEC = Codec.STRING.xmap(
                name -> {
                    String lower = name.toLowerCase(Locale.ROOT);
                    return switch (lower) {
                        case "temperature", "temp" -> TEMPERATURE;
                        case "humidity", "vegetation", "precip", "precipitation" -> HUMIDITY;
                        case "continentalness", "continents", "elevation", "elev" -> CONTINENTALNESS;
                        case "erosion" -> EROSION;
                        case "weirdness", "ridges" -> WEIRDNESS;
                        default -> throw new IllegalArgumentException("Unknown climate axis: " + name);
                    };
                },
                ClimateAxis::getSerializedName
        );

        private final String name;

        ClimateAxis(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return this.name;
        }
    }

    public static final MapCodec<TerrainDiffusionClimateFunction> CODEC = RecordCodecBuilder.mapCodec(
            instance -> instance.group(
                    ClimateAxis.CODEC.fieldOf("axis").forGetter(TerrainDiffusionClimateFunction::getAxis)
            ).apply(instance, TerrainDiffusionClimateFunction::new)
    );

    // ------------------------------------------------------------------
    // Mapping anchors: AI value -> vanilla parameter in [-1.0, 1.0].
    // ------------------------------------------------------------------
    private static final float[] TEMP_C         = {-20f,   -10f,     0f,    10f,   15f,   20f,   28f,   36f,  45f};
    private static final float[] TEMP_PARAM     = {-1.0f, -0.55f, -0.18f,  0.00f, 0.10f, 0.20f, 0.55f, 0.85f, 1.0f};

    private static final float[] PRECIP_MM      = {0f, 150f, 400f, 700f, 1100f, 1600f, 2200f, 3000f};
    private static final float[] HUMIDITY_PARAM = {-1f, -0.6f, -0.2f, 0.1f, 0.4f, 0.7f, 0.9f, 1f};

    private static final float[] ELEV_M          = {-1000f, -250f, -50f,   -5f,     0f,      1f,   50f,  300f, 1500f, 4000f, 9000f};
    private static final float[] CONTINENT_PARAM = {-1.00f, -0.60f, -0.35f, -0.20f, -0.15f,  0.05f, 0.20f, 0.40f, 0.80f, 0.95f, 1.00f};

    private static final FastNoiseLite EROSION_NOISE   = new FastNoiseLite(1111);
    private static final FastNoiseLite WEIRDNESS_NOISE = new FastNoiseLite(2222);
    private static final FastNoiseLite TEMP_NOISE      = new FastNoiseLite(3333);
    private static final FastNoiseLite HUMIDITY_NOISE  = new FastNoiseLite(4444);
    private static final FastNoiseLite REGIONAL_TEMP_NOISE     = new FastNoiseLite(5555);
    private static final FastNoiseLite REGIONAL_HUMIDITY_NOISE = new FastNoiseLite(6666);

    static {
        float regionalScale = (float) Math.max(500, TerrainDiffusionConfig.regionalClimateScale());
        float regionalFreq = 1.0f / regionalScale;

        REGIONAL_TEMP_NOISE.SetNoiseType(FastNoiseLite.NoiseType.Perlin);
        REGIONAL_TEMP_NOISE.SetFractalType(FastNoiseLite.FractalType.FBm);
        REGIONAL_TEMP_NOISE.SetFractalOctaves(2);
        REGIONAL_TEMP_NOISE.SetFrequency(regionalFreq);

        REGIONAL_HUMIDITY_NOISE.SetNoiseType(FastNoiseLite.NoiseType.Perlin);
        REGIONAL_HUMIDITY_NOISE.SetFractalType(FastNoiseLite.FractalType.FBm);
        REGIONAL_HUMIDITY_NOISE.SetFractalOctaves(2);
        REGIONAL_HUMIDITY_NOISE.SetFrequency(regionalFreq);

        EROSION_NOISE.SetNoiseType(FastNoiseLite.NoiseType.Perlin);
        EROSION_NOISE.SetFractalType(FastNoiseLite.FractalType.FBm);
        EROSION_NOISE.SetFractalOctaves(3);
        EROSION_NOISE.SetFrequency(0.005f);

        WEIRDNESS_NOISE.SetNoiseType(FastNoiseLite.NoiseType.Perlin);
        WEIRDNESS_NOISE.SetFractalType(FastNoiseLite.FractalType.FBm);
        WEIRDNESS_NOISE.SetFractalOctaves(3);
        WEIRDNESS_NOISE.SetFrequency(0.004f);

        TEMP_NOISE.SetNoiseType(FastNoiseLite.NoiseType.Perlin);
        TEMP_NOISE.SetFractalType(FastNoiseLite.FractalType.FBm);
        TEMP_NOISE.SetFractalOctaves(2);
        TEMP_NOISE.SetFrequency(0.01f);

        HUMIDITY_NOISE.SetNoiseType(FastNoiseLite.NoiseType.Perlin);
        HUMIDITY_NOISE.SetFractalType(FastNoiseLite.FractalType.FBm);
        HUMIDITY_NOISE.SetFractalOctaves(2);
        HUMIDITY_NOISE.SetFrequency(0.01f);
    }

    private final ClimateAxis axis;

    public TerrainDiffusionClimateFunction(ClimateAxis axis) {
        this.axis = axis;
    }

    public ClimateAxis getAxis() {
        return this.axis;
    }

    @Override
    public double compute(DensityFunction.FunctionContext context) {
        return sampleAxis(context.blockX(), context.blockY(), context.blockZ(), this.axis);
    }

    @Override
    public void fillArray(double[] array, DensityFunction.ContextProvider provider) {
        for (int i = 0; i < array.length; i++) {
            array[i] = this.compute(provider.forIndex(i));
        }
    }

    @Override
    public DensityFunction mapAll(DensityFunction.Visitor visitor) {
        return visitor.apply(this);
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
        return KeyDispatchDataCodec.of(CODEC);
    }

    public static double sampleAxis(int blockX, int blockZ, ClimateAxis axis) {
        return sampleAxis(blockX, 64, blockZ, axis);
    }

    public static double sampleAxis(int blockX, int blockY, int blockZ, ClimateAxis axis) {
        if (axis == ClimateAxis.EROSION) {
            return Math.max(-1.0, Math.min(1.0, EROSION_NOISE.GetNoise(blockX, blockZ)));
        }
        if (axis == ClimateAxis.WEIRDNESS) {
            return Math.max(-1.0, Math.min(1.0, WEIRDNESS_NOISE.GetNoise(blockX, blockZ)));
        }

        float temp, precip, elev;

        if (LocalTerrainProvider.isMacroQuery()) {
            LocalTerrainProvider.CoarseSample sample = LocalTerrainProvider.getCoarseSample(blockX, blockZ);
            temp = sample.temperature();
            precip = sample.precipitation();
            elev = sample.elevationMeters();
        } else {
            int tileSize = TerrainDiffusionConfig.tileSize();
            int tileShift = Integer.numberOfTrailingZeros(tileSize);
            int blockStartX = (blockX >> tileShift) << tileShift;
            int blockStartZ = (blockZ >> tileShift) << tileShift;
            int blockEndX = blockStartX + tileSize;
            int blockEndZ = blockStartZ + tileSize;

            // FORCE high-res data for perfectly synced chunk borders
            LocalTerrainProvider.HeightmapData data = LocalTerrainProvider.getInstance()
                    .fetchHeightmap(blockStartZ, blockStartX, blockEndZ, blockEndX);

            int localX = Math.max(0, Math.min(data.width - 1, blockX - blockStartX));
            int localZ = Math.max(0, Math.min(data.height - 1, blockZ - blockStartZ));
            temp = data.temperature[localZ][localX];
            precip = data.precipitation[localZ][localX];
            elev = data.elevationMeters[localZ][localX];
        }

        // Physical mountain cooling based on real elevation meters from the AI model (scale-invariant):
        // - Hills & low mountains (< 1000m): 0.0 cooling. At scale 6 they form towering 200-block STONE cliffs (Stony Peaks / Windswept).
        // - Mid-alpine slopes (1000m - 2200m): moderate cooling (0.0 to 0.30), allowing stony peaks in warm zones and pine groves in cool zones.
        // - High alpine summits (> 2200m): cooling up to 0.50, forming snow caps (Snowy Slopes / Jagged Peaks) strictly on top.
        float heightCooling = elev > 1000f ? Math.min(0.50f, (elev - 1000f) * 0.00030f) : 0.0f;

        // Additional 3D cooling for samples high in the air above the surface (e.g. flying / sky)
        int surfaceY = HeightConverter.convertToMinecraftHeight((short) Math.max(0, Math.round(elev)));
        if (blockY > surfaceY) {
            heightCooling += Math.min(0.25f, (blockY - surfaceY) * 0.003f);
        }

        double value = 0.0;
        switch (axis) {
            case TEMPERATURE:
                value = remap(temp, TEMP_C, TEMP_PARAM)
                        + (REGIONAL_TEMP_NOISE.GetNoise(blockX, blockZ) * 0.45f)
                        + (TEMP_NOISE.GetNoise(blockX, blockZ) * 0.10f)
                        - heightCooling;
                break;
            case HUMIDITY:
                value = remap(precip, PRECIP_MM, HUMIDITY_PARAM)
                        + (REGIONAL_HUMIDITY_NOISE.GetNoise(blockX, blockZ) * 0.45f)
                        + (HUMIDITY_NOISE.GetNoise(blockX, blockZ) * 0.10f);
                break;
            case CONTINENTALNESS:
                value = remap(elev, ELEV_M, CONTINENT_PARAM);
                break;
            default:
                break;
        }
        return Math.max(-1.0, Math.min(1.0, value));
    }

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
}
