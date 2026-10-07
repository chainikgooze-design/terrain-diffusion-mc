package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.world.WorldScaleManager;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.VerticalAnchor;
import net.minecraft.world.level.levelgen.placement.HeightRangePlacement;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.stream.Stream;

/**
 * NeoForge mixin that fixes ore generation on tall AI mountains.
 *
 * <p>AI mountains can reach Y=600+ when the world scale is &gt; 1, but vanilla ores
 * (via biome modifiers / placed features) cap their placement below Y=320. When the
 * mod's {@link WorldScaleManager} scale is greater than 1, this mixin re-scales the
 * resolved {@code minInclusive} / {@code maxInclusive} Y bounds by that factor, so an
 * ore configured for Y=320 generates up to Y=640 at 2x, matching the stretched terrain.
 *
 * <p><b>Mapping note:</b> targets the Mojang-mapped {@code HeightRangePlacement}
 * fields {@code minInclusive} / {@code maxInclusive} and method {@code getPositions}.
 */
@Mixin(HeightRangePlacement.class)
public abstract class HeightRangePlacementMixin {

    @Shadow @Final private VerticalAnchor minInclusive;
    @Shadow @Final private VerticalAnchor maxInclusive;

    @Inject(method = "getPositions", at = @At("HEAD"), cancellable = true)
    private void terrainDiffusion$scaleHeightRange(PlacementContext context, RandomSource random,
                                                   BlockPos pos, CallbackInfoReturnable<Stream<BlockPos>> cir) {
        int scale = WorldScaleManager.getCurrentScale();
        if (scale <= 1) {
            return; // Vanilla path - no scaling needed.
        }

        // Resolve the vanilla bounds, then stretch them by the world scale so ores
        // appear inside the tall AI-generated mountains. PlacementContext extends
        // WorldGenerationContext, which is what resolveY() consumes in 1.21.1.
        int minY = this.minInclusive.resolveY(context) * scale;
        int maxY = this.maxInclusive.resolveY(context) * scale;

        if (minY > maxY) {
            cir.setReturnValue(Stream.empty());
            return;
        }

        cir.setReturnValue(Stream.of(pos.atY(Mth.randomBetweenInclusive(random, minY, maxY))));
    }
}