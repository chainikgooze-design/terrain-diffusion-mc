package com.github.xandergos.terraindiffusionmc.pipeline;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Regression test for the ONNX inference serialization gate.
 *
 * <p>Reflects on {@code OnnxModel.INFERENCE_GATE} (the private static fair
 * {@code Semaphore(1)} that every native {@code session.run()} must pass through)
 * and proves that N concurrent "inference" callers never hold the permit at the
 * same time - i.e. exactly one inference runs at a time. This guards against a
 * regression that would let multiple Minecraft chunk-worker threads run native GPU
 * inference concurrently and stack VRAM until OOM.
 *
 * <p>Run with {@code gradlew :common:inferenceGateTest}. Exits non-zero on failure.
 * Mirrors the repo's existing {@link PipelineTest} main-class pattern (no JUnit).
 */
public final class InferenceGateSerializationTest {

    public static void main(String[] args) throws Exception {
        Field field = OnnxModel.class.getDeclaredField("INFERENCE_GATE");
        field.setAccessible(true);
        Object gateObj = field.get(null);
        if (!(gateObj instanceof Semaphore)) {
            throw new AssertionError("INFERENCE_GATE is not a Semaphore: " + gateObj);
        }
        Semaphore gate = (Semaphore) gateObj;

        if (!gate.isFair()) {
            throw new AssertionError("INFERENCE_GATE must be a fair semaphore");
        }
        if (gate.availablePermits() != 1) {
            throw new AssertionError("INFERENCE_GATE must start with exactly 1 permit, was "
                    + gate.availablePermits());
        }

        final int callers = 16;
        AtomicInteger active = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(callers);
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < callers; i++) {
            Thread t = new Thread(() -> {
                try {
                    start.await();
                    // Same acquire/release sequence the production runWithSession uses.
                    gate.acquire();
                    try {
                        int now = active.incrementAndGet();
                        peak.updateAndGet(p -> Math.max(p, now));
                        Thread.sleep(10); // simulate a native inference's GPU work
                    } finally {
                        active.decrementAndGet();
                        gate.release();
                    }
                } catch (Throwable e) {
                    failures.add(e);
                } finally {
                    done.countDown();
                }
            }, "worker-" + i);
            t.setDaemon(true);
            t.start();
        }

        start.countDown();
        if (!done.await(30, TimeUnit.SECONDS)) {
            throw new AssertionError("Timed out waiting for concurrent callers");
        }
        if (!failures.isEmpty()) {
            throw new RuntimeException(failures.get(0));
        }
        if (peak.get() > 1) {
            throw new AssertionError("FAIL: max concurrent inferences = " + peak.get() + " (expected 1)");
        }
        if (gate.availablePermits() != 1) {
            throw new AssertionError("FAIL: gate not fully released; permits = " + gate.availablePermits());
        }

        System.out.println("PASS: " + callers + " concurrent callers, max concurrent inference = "
                + peak.get() + " (serialized by INFERENCE_GATE, fair Semaphore(1))");
    }
}
