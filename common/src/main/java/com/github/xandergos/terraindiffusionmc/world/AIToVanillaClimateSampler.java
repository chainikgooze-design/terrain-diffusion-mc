package com.github.xandergos.terraindiffusionmc.world;

import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.DensityFunctions;

import java.util.List;

/**
 * Builds the vanilla {@link Climate.Sampler} backed by {@link TerrainDiffusionClimateFunction}
 * for the MultiNoiseBiomeSource.
 */
public final class AIToVanillaClimateSampler {

    /**
     * Builds the vanilla {@link Climate.Sampler} that feeds the AI climate into the
     * MultiNoiseBiomeSource.
     */
    public Climate.Sampler buildSampler() {
        return new Climate.Sampler(
                new TerrainDiffusionClimateFunction(TerrainDiffusionClimateFunction.ClimateAxis.TEMPERATURE),
                new TerrainDiffusionClimateFunction(TerrainDiffusionClimateFunction.ClimateAxis.HUMIDITY),
                new TerrainDiffusionClimateFunction(TerrainDiffusionClimateFunction.ClimateAxis.CONTINENTALNESS),
                new TerrainDiffusionClimateFunction(TerrainDiffusionClimateFunction.ClimateAxis.EROSION),
                DensityFunctions.zero(), // depth remains neutral (mostly impacts caves)
                new TerrainDiffusionClimateFunction(TerrainDiffusionClimateFunction.ClimateAxis.WEIRDNESS),
                List.of() // no spawn target; findSpawnPosition() returns BlockPos.ZERO
        );
    }
}
