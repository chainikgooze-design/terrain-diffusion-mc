package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.world.WorldScaleManager;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.heightproviders.HeightProvider;
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
 * Common mixin that fixes ore generation on tall AI mountains.
 *
 * <p>AI mountains can reach Y=600+ when the world scale is &gt; 1, but vanilla ores
 * (via biome modifiers / placed features) cap their placement below Y=320. When the
 * mod's {@link WorldScaleManager} scale is greater than 1, this mixin re-scales the
 * sampled Y position for mountains so ores appear inside tall AI-generated mountains.
 */
@Mixin(HeightRangePlacement.class)
public abstract class HeightRangePlacementMixin {

    @Shadow @Final private HeightProvider height;

    @Inject(method = "getPositions", at = @At("HEAD"), cancellable = true)
    private void terrainDiffusion$scaleHeightRange(PlacementContext context, RandomSource random,
                                                   BlockPos pos, CallbackInfoReturnable<Stream<BlockPos>> cir) {
        int scale = WorldScaleManager.getCurrentScale();
        if (scale <= 1) {
            return; // Vanilla path - no scaling needed.
        }

        int sampledY = this.height.sample(random, context);
        // Deep underground ores (Y <= 64) remain at their natural depths.
        // Ores above sea level (mountains/hills) are stretched proportionally by the world scale
        // so that coal, iron, and emeralds generate all the way to the summit of tall mountains.
        int scaledY;
        if (sampledY > 64) {
            scaledY = 64 + (sampledY - 64) * scale;
        } else {
            scaledY = sampledY;
        }

        cir.setReturnValue(Stream.of(pos.atY(scaledY)));
    }
}
