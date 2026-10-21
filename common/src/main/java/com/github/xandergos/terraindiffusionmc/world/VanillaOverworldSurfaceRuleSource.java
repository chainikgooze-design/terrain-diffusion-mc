package com.github.xandergos.terraindiffusionmc.world;

import com.mojang.serialization.MapCodec;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.SurfaceRules;

import java.lang.reflect.Method;

/**
 * Custom {@link SurfaceRules.RuleSource} that delegates surface block placement
 * to the vanilla Minecraft overworld surface rules.
 *
 * <p>Registered under {@code terrain-diffusion-mc:vanilla_overworld}.
 * This avoids copying thousands of lines of vanilla surface rules (badlands strata,
 * mountain snow, beach sand, gravel, deepslate) into JSON.
 */
public final class VanillaOverworldSurfaceRuleSource implements SurfaceRules.RuleSource {

    public static final MapCodec<VanillaOverworldSurfaceRuleSource> CODEC =
            MapCodec.unit(VanillaOverworldSurfaceRuleSource::new);

    public static final KeyDispatchDataCodec<VanillaOverworldSurfaceRuleSource> KEY_CODEC =
            KeyDispatchDataCodec.of(CODEC);

    public static final VanillaOverworldSurfaceRuleSource INSTANCE =
            new VanillaOverworldSurfaceRuleSource();

    private static volatile SurfaceRules.RuleSource overworldDelegate;

    public VanillaOverworldSurfaceRuleSource() {
    }

    public static void setOverworldDelegate(SurfaceRules.RuleSource delegate) {
        overworldDelegate = delegate;
    }

    public static SurfaceRules.RuleSource getOverworldDelegate() {
        if (overworldDelegate != null) {
            return overworldDelegate;
        }

        // Development environment fallback: loads SurfaceRuleData.overworld() via reflection
        // without crashing production runtimes where net.minecraft.data.* is stripped.
        try {
            Class<?> clazz = Class.forName("net.minecraft.data.worldgen.SurfaceRuleData");
            Method method = clazz.getMethod("overworld");
            overworldDelegate = (SurfaceRules.RuleSource) method.invoke(null);
            return overworldDelegate;
        } catch (Throwable ignored) {
        }

        return null;
    }

    @Override
    public KeyDispatchDataCodec<? extends SurfaceRules.RuleSource> codec() {
        return KEY_CODEC;
    }

    @Override
    public SurfaceRules.SurfaceRule apply(SurfaceRules.Context context) {
        SurfaceRules.RuleSource delegate = getOverworldDelegate();
        if (delegate != null) {
            return delegate.apply(context);
        }
        return (x, y, z) -> null;
    }
}
