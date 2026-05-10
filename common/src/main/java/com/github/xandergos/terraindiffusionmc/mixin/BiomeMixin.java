package com.github.xandergos.terraindiffusionmc.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Biome.class)
public abstract class BiomeMixin {
    @Shadow
    public abstract float getBaseTemperature();

    @Shadow
    public abstract boolean hasPrecipitation();

    @Inject(method = "getPrecipitationAt", at = @At("HEAD"), cancellable = true)
    private void terrainDiffusionMc$overridePrecipitation(BlockPos pos, CallbackInfoReturnable<Biome.Precipitation> cir) {
        if (!this.hasPrecipitation()) {
            cir.setReturnValue(Biome.Precipitation.NONE);
            return;
        }

        // In 1.20.1, keep this mixin independent from LocalTerrainProvider.
        // The 1.21.x biome-id lookup path relied on provider helpers that are not
        // present in this backport. This still prevents vanilla altitude snow in
        // non-snowy Terrain Diffusion biomes.
        if (this.getBaseTemperature() >= 0.15F) {
            cir.setReturnValue(Biome.Precipitation.RAIN);
        }
    }
}
