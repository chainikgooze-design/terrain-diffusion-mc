package com.github.xandergos.terraindiffusionmc.pipeline;

/**
 * Scoped context for macro queries (spawn search, /locate, structure placement checks).
 *
 * <p>When active, signals that the calling thread is only probing for structures,
 * spawn, or command locations and only needs coarse approximations, avoiding heavy
 * full-resolution GPU decodes.
 *
 * <p>Normal chunk generation phases (ChunkStatus.BIOMES, ChunkStatus.NOISE,
 * ChunkStatus.SURFACE, ChunkStatus.FEATURES) never run inside a {@code MacroQueryScope},
 * guaranteeing that chunk blocks and biomes always use full high-resolution data.
 */
public final class MacroQueryScope implements AutoCloseable {
    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

    public static boolean isActive() {
        return DEPTH.get() > 0;
    }

    public static MacroQueryScope open() {
        DEPTH.set(DEPTH.get() + 1);
        return new MacroQueryScope();
    }

    public static void enter() {
        DEPTH.set(DEPTH.get() + 1);
    }

    public static void exit() {
        int current = DEPTH.get();
        if (current <= 1) {
            DEPTH.remove();
        } else {
            DEPTH.set(current - 1);
        }
    }

    @Override
    public void close() {
        exit();
    }
}
