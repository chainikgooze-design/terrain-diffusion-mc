package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.pipeline.MacroQueryScope;
import com.mojang.datafixers.util.Pair;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.Structure;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChunkGenerator.class)
public class ChunkGeneratorMixin {

    @Inject(method = "findNearestMapStructure", at = @At("HEAD"))
    private void td$enterMacroQuery(ServerLevel level, HolderSet<Structure> structure, BlockPos pos,
                                    int searchRadius, boolean skipKnownStructures,
                                    CallbackInfoReturnable<Pair<BlockPos, Holder<Structure>>> cir) {
        MacroQueryScope.enter();
    }

    @Inject(method = "findNearestMapStructure", at = @At("RETURN"))
    private void td$exitMacroQuery(ServerLevel level, HolderSet<Structure> structure, BlockPos pos,
                                   int searchRadius, boolean skipKnownStructures,
                                   CallbackInfoReturnable<Pair<BlockPos, Holder<Structure>>> cir) {
        MacroQueryScope.exit();
    }
}
