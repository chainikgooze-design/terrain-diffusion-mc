package com.github.xandergos.terraindiffusionmc.world;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

public final class WorldScaleSettingsState extends SavedData {

    private static final Codec<WorldScaleSettingsState> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.optionalFieldOf("scale", WorldScaleManager.DEFAULT_SCALE)
                    .forGetter(WorldScaleSettingsState::getScale),
            Codec.BOOL.optionalFieldOf("explicit_scale", false)
                    .forGetter(WorldScaleSettingsState::hasExplicitScale)
    ).apply(instance, WorldScaleSettingsState::new));

    private int scale;
    private boolean explicitScale;

    private WorldScaleSettingsState(int configuredScale, boolean hasExplicitScale) {
        this.scale = WorldScaleManager.clampScale(configuredScale);
        this.explicitScale = hasExplicitScale;
    }

    public WorldScaleSettingsState() {
        this(WorldScaleManager.DEFAULT_SCALE, false);
    }

    public static WorldScaleSettingsState createDefault() {
        return new WorldScaleSettingsState(WorldScaleManager.DEFAULT_SCALE, false);
    }

    public static WorldScaleSettingsState get(ServerLevel world) {
        return world.getDataStorage().computeIfAbsent(
                WorldScaleSettingsState::fromNbt,
                WorldScaleSettingsState::new,
                "world_scale_settings"
        );
    }

    public static WorldScaleSettingsState fromNbt(CompoundTag nbt) {
        return CODEC.parse(NbtOps.INSTANCE, nbt)
                .result()
                .orElseGet(WorldScaleSettingsState::createDefault);
    }

    @Override
    public CompoundTag save(CompoundTag nbt) {
        CODEC.encodeStart(NbtOps.INSTANCE, this)
                .result()
                .ifPresent(encoded -> nbt.merge((CompoundTag) encoded));
        return nbt;
    }

    public int getScale() {
        return scale;
    }

    public boolean hasExplicitScale() {
        return explicitScale;
    }

    public void setScale(int configuredScale) {
        this.scale = WorldScaleManager.clampScale(configuredScale);
        this.explicitScale = true;
        setDirty();
    }
}
