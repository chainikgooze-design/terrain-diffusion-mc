package com.github.xandergos.terraindiffusionmc.world;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;

import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Data-driven AI -> vanilla biome bridge.
 *
 * <p>The old implementation resolved biomes from a hardcoded {@code climate -> biome id}
 * table produced by {@link com.github.xandergos.terraindiffusionmc.pipeline.BiomeClassifier}.
 * That approach is deprecated. This source instead wraps the vanilla
 * {@link MultiNoiseBiomeSource} (overworld preset) and feeds it AI climate through
 * {@link AIToVanillaClimateSampler}. Because the pixels are still resolved through the
 * vanilla MultiNoise machinery, datapack biomes and NeoForge biome modifiers (ore
 * geologies) work natively.
 */
public class TerrainDiffusionBiomeSource extends BiomeSource {
    public static final MapCodec<TerrainDiffusionBiomeSource> CODEC = RecordCodecBuilder.mapCodec(
            (instance) -> instance.group(
                    // Dynamic HolderGetter lookups only - no static BuiltInRegistries access
                    // (required for NeoForge re-creating the world at reload and for data packs).
                    RegistryOps.retrieveGetter(Registries.BIOME),
                    RegistryOps.retrieveGetter(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
            ).apply(instance, instance.stable(TerrainDiffusionBiomeSource::new)));

    private final Climate.Sampler sampler = new AIToVanillaClimateSampler().buildSampler();
    private final MultiNoiseBiomeSource delegate;

    public TerrainDiffusionBiomeSource(HolderGetter<Biome> biomeGetter,
                                       HolderGetter<MultiNoiseBiomeSourceParameterList> parameterListGetter) {
        // Standard overworld parameter list: all vanilla (and data-pack) biomes become
        // selectable through the AI climate sampler.
        Holder<MultiNoiseBiomeSourceParameterList> overworld =
                parameterListGetter.getOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD);
        this.delegate = MultiNoiseBiomeSource.createFromPreset(overworld);
    }

    @Override
    protected MapCodec<? extends BiomeSource> codec() {
        return CODEC;
    }

    @Override
    protected Stream<Holder<Biome>> collectPossibleBiomes() {
        return this.delegate.possibleBiomes().stream();
    }

    @Override
    public Holder<Biome> getNoiseBiome(int x, int y, int z, Climate.Sampler noise) {
        return this.delegate.getNoiseBiome(x, y, z, this.sampler);
    }

    @Override
    public Pair<BlockPos, Holder<Biome>> findClosestBiome3d(BlockPos origin, int radius,
                                                            int horizontalBlockCheckInterval,
                                                            int verticalBlockCheckInterval,
                                                            Predicate<Holder<Biome>> predicate,
                                                            Climate.Sampler noiseSampler,
                                                            LevelReader world) {
        try (var ignored = com.github.xandergos.terraindiffusionmc.pipeline.MacroQueryScope.open()) {
            return this.delegate.findClosestBiome3d(origin, radius, horizontalBlockCheckInterval,
                    verticalBlockCheckInterval, predicate, this.sampler, world);
        }
    }

    @Override
    public Pair<BlockPos, Holder<Biome>> findBiomeHorizontal(int x, int y, int z, int radius,
                                                             int blockCheckInterval,
                                                             Predicate<Holder<Biome>> predicate,
                                                             RandomSource random, boolean bl,
                                                             Climate.Sampler noiseSampler) {
        try (var ignored = com.github.xandergos.terraindiffusionmc.pipeline.MacroQueryScope.open()) {
            return this.delegate.findBiomeHorizontal(x, y, z, radius, blockCheckInterval,
                    predicate, random, bl, this.sampler);
        }
    }
}