package com.github.xandergos.terraindiffusionmc.world;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider.HeightmapData;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

public class TerrainDiffusionDensityFunction implements DensityFunction.SimpleFunction {

    public static final TerrainDiffusionDensityFunction INSTANCE = new TerrainDiffusionDensityFunction();

    /**
     * Minecraft 1.20.1 registers density-function types as Codec instances.
     * The instance codec is deliberately separate from the key-dispatch codec
     * returned by DensityFunction#codec().
     */
    public static final Codec<TerrainDiffusionDensityFunction> CODEC =
            MapCodec.unit(INSTANCE).codec();

    private static final KeyDispatchDataCodec<TerrainDiffusionDensityFunction> KEY_DISPATCH_CODEC =
            KeyDispatchDataCodec.of(MapCodec.unit(INSTANCE));

    @Override
    public double compute(DensityFunction.FunctionContext pos) {
        int x = pos.blockX();
        int z = pos.blockZ();
        int y = pos.blockY();

        int tileSize = TerrainDiffusionConfig.tileSize();
        int tileShift = Integer.numberOfTrailingZeros(tileSize);

        int tileX = x >> tileShift;
        int tileZ = z >> tileShift;

        int blockStartX = tileX << tileShift;
        int blockStartZ = tileZ << tileShift;

        int blockEndX = blockStartX + tileSize;
        int blockEndZ = blockStartZ + tileSize;

        HeightmapData data = LocalTerrainProvider.getInstance()
                .fetchHeightmap(blockStartZ, blockStartX, blockEndZ, blockEndX);

        if (data == null || data.heightmap == null) {
            return 0.0;
        }

        int localX = Math.max(0, Math.min(data.width - 1, x - blockStartX));
        int localZ = Math.max(0, Math.min(data.height - 1, z - blockStartZ));

        int targetHeight = HeightConverter.convertToMinecraftHeight(
                data.heightmap[localZ][localX]
        );

        return targetHeight - y;
    }

    @Override
    public double minValue() {
        return -64;
    }

    @Override
    public double maxValue() {
        return 1024;
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        return KEY_DISPATCH_CODEC;
    }
}
