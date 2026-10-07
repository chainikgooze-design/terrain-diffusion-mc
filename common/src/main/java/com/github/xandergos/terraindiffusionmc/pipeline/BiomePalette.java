package com.github.xandergos.terraindiffusionmc.pipeline;

/**
 * @deprecated Legacy short biome-id palette from the hardcoded classifier. World
 *             generation now resolves biomes through the vanilla
 *             {@code MultiNoiseBiomeSource} + {@code AIToVanillaClimateSampler};
 *             this palette is kept only for the terrain explorer UI.
 */
@Deprecated
public final class BiomePalette {

    static final short PLAINS   = 1,
            SNOWY_PLAINS        = 3,
            DESERT              = 5,
            SWAMP               = 6,
            FOREST              = 8,
            TAIGA               = 15,
            SNOWY_TAIGA         = 16,
            SAVANNA             = 17,
            WINDSWEPT_HILLS     = 19,
            JUNGLE              = 23,
            BADLANDS            = 26,
            MEADOW              = 29,
            GROVE               = 31,
            SNOWY_SLOPES        = 32,
            FROZEN_PEAKS        = 33,
            STONY_PEAKS         = 35,
            WARM_OCEAN          = 41,
            OCEAN               = 44,
            DEEP_OCEAN          = 45,
            COLD_OCEAN          = 46,
            FROZEN_OCEAN        = 48,
            FOREST_SPARSE       = 108,
            TAIGA_SPARSE        = 115,
            SNOWY_TAIGA_SPARSE  = 116;

}
