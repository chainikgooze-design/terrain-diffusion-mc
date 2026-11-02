package com.github.xandergos.terraindiffusionmc.world;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider.HeightmapData;
import com.mojang.serialization.MapCodec;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;


public class TerrainDiffusionDensityFunction implements DensityFunction {

    public static final MapCodec<TerrainDiffusionDensityFunction> CODEC =
            MapCodec.unit(TerrainDiffusionDensityFunction::new);

    public static final TerrainDiffusionDensityFunction INSTANCE =
            new TerrainDiffusionDensityFunction();

    public static int applyRiverCarving(int x, int z, int targetHeight) {
        // Rivers only carve in lowlands, plains, and valleys (targetHeight <= 85)
        if (targetHeight <= 63) {
            return targetHeight; // Already at or below sea level
        }
        if (targetHeight > 85) {
            return targetHeight; // High mountains - no rivers
        }

        float weird = TerrainDiffusionClimateFunction.getWeirdnessNoise(x, z);
        float riverHalfWidth = 0.055f;
        float absWeird = Math.abs(weird);
        if (absWeird < riverHalfWidth) {
            float t = 1.0f - (absWeird / riverHalfWidth);
            // Smoothstep curve for natural riverbed cross-section
            float w = t * t * (3.0f - 2.0f * t);
            // Smoothly fade out carving depth between Y=72 and Y=85
            if (targetHeight > 72) {
                float fade = 1.0f - ((targetHeight - 72f) / 13.0f);
                w *= fade;
            }
            // Carve down towards Y=58 (5 blocks of water below sea level 63)
            return (int) Math.round(targetHeight * (1.0f - w) + 58.0f * w);
        }
        return targetHeight;
    }

    @Override
    public double compute(DensityFunction.FunctionContext pos) {
        int x = pos.blockX();
        int z = pos.blockZ();
        int y = pos.blockY();

        // Macro queries (/locate structure, spawn search) read the coarse map.
        // Chunk generation phases never run under MacroQueryScope, guaranteeing High-Res.
        if (LocalTerrainProvider.isMacroQuery()) {
            int coarseHeight = LocalTerrainProvider.getCoarseHeight(x, z);
            return applyRiverCarving(x, z, coarseHeight) - y;
        }

        int tileSize = TerrainDiffusionConfig.tileSize();
        int tileShift = Integer.numberOfTrailingZeros(tileSize);
        int blockStartX = (x >> tileShift) << tileShift;
        int blockStartZ = (z >> tileShift) << tileShift;
        int blockEndX = blockStartX + tileSize;
        int blockEndZ = blockStartZ + tileSize;

        HeightmapData data = LocalTerrainProvider.getInstance()
                .fetchHeightmap(blockStartZ, blockStartX, blockEndZ, blockEndX);

        int localX = Math.max(0, Math.min(data.width - 1, x - blockStartX));
        int localZ = Math.max(0, Math.min(data.height - 1, z - blockStartZ));
        int targetHeight = HeightConverter.convertToMinecraftHeight(data.heightmap[localZ][localX]);
        targetHeight = applyRiverCarving(x, z, targetHeight);

        return targetHeight - y;
    }

    private static final class FillContext {
        int blockStartX, blockStartZ, blockEndX, blockEndZ;
        HeightmapData data;

        void update(int x, int z) {
            if (x < blockStartX || x >= blockEndX) this.init(x, z);
            if (z < blockStartZ || z >= blockEndZ) this.init(x, z);
        }

        void init(int x, int z) {
            int tileSize = TerrainDiffusionConfig.tileSize();
            int tileShift = Integer.numberOfTrailingZeros(tileSize);

            int tileX = x >> tileShift;
            int tileZ = z >> tileShift;

            this.blockStartX = tileX << tileShift;
            this.blockStartZ = tileZ << tileShift;
            this.blockEndX = blockStartX + tileSize;
            this.blockEndZ = blockStartZ + tileSize;

            this.data = LocalTerrainProvider.getInstance()
                .fetchHeightmap(blockStartZ, blockStartX, blockEndZ, blockEndX);
        }
    }

    @Override
    public void fillArray(double[] densities, DensityFunction.ContextProvider applier) {
        if (densities.length == 0) return;

        FillContext ctx = new FillContext();
        DensityFunction.FunctionContext pos = applier.forIndex(0);
        int x = pos.blockX();
        int z = pos.blockZ();
        int y = pos.blockY();
        ctx.init(x, z);

        for (int i = 0; i < densities.length; i++) {
            pos = applier.forIndex(i);
            x = pos.blockX();
            z = pos.blockZ();
            y = pos.blockY();
            ctx.update(x, z);

            HeightmapData data = ctx.data;
            if (data == null || data.heightmap == null) {
                densities[i] = -y;
                continue;
            }

            int localX = Math.max(0, Math.min(data.width  - 1, x - ctx.blockStartX));
            int localZ = Math.max(0, Math.min(data.height - 1, z - ctx.blockStartZ));

            int targetHeight = HeightConverter
                .convertToMinecraftHeight(data.heightmap[localZ][localX]);
            targetHeight = applyRiverCarving(x, z, targetHeight);
            densities[i] = targetHeight - y;
        }
    }

    @Override
    public DensityFunction mapAll(DensityFunction.Visitor visitor) {
        return visitor.apply(this);
    }

    @Override
    public double minValue() {
        return -64;
    }

    @Override
    public double maxValue() {
        return 2100;
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        return KeyDispatchDataCodec.of(CODEC);
    }
}
