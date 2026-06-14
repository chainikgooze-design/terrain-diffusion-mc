package com.github.xandergos.terraindiffusionmc.mixin;

import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the private {@code climateSettings} field on {@link Biome} so the
 * dynamic biome classifier can read downfall and other climate properties.
 */
@Mixin(Biome.class)
public interface BiomeAccessor {
    @Accessor("climateSettings")
    Biome.ClimateSettings terrainDiffusion$getClimateSettings();
}
