package io.dynamis.audio.test;

import io.dynamis.audio.core.*;
import io.dynamis.audio.designer.MixSnapshotManager;
import io.dynamis.audio.dsp.*;
import io.dynamis.audio.dsp.device.NullAudioDevice;
import io.dynamis.audio.api.AcousticConstants;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import static org.assertj.core.api.Assertions.*;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;

/**
 * CI enforcement harness for the zero-allocation contract on the DSP render path.
 *
 * STRATEGY:
 *   Render N warm-up blocks to allow JIT compilation and class loading.
 *   Then render M measured blocks and assert that heap allocation during
 *   the measured window is below the allowed threshold.
 *
 * WHY NOT ZERO EXACTLY:
 *   JVM accounting via MemoryMXBean is approximate. A threshold of 0 bytes
 *   produces false positives from GC accounting artifacts on some JVMs.
 *   The threshold is set conservatively: any genuine allocation from a
 *   new object in renderBlock() will far exceed 512 bytes per block.
 *
 * This test is the CI enforcement mechanism for the GC contract described
 * in the architecture document, Section 5.3, action 2b.
 *
 * NOTE: This test measures allocation trends, not absolute JVM internals.
 * It is designed to catch regressions (objects introduced into renderBlock())
 * rather than provide byte-precise accounting.
 */
class NoAllocRenderHarnessTest {

    private static final int WARMUP_BLOCKS = 500;
    private static final int MEASURED_BLOCKS = 200;

    // Max bytes allocated per measured block before the test fails.
    // Set conservatively: any real allocation will dwarf this.
    private static final long MAX_BYTES_PER_BLOCK = 512L;

    private SoftwareMixer mixer;

    @BeforeEach
    void setUp() throws Exception {
        AcousticSnapshotManager mgr = new AcousticSnapshotManager();
        AcousticEventQueueImpl queue = new AcousticEventQueueImpl(64);
        mgr.publish();
        NullAudioDevice device = new NullAudioDevice();
        device.open(AcousticConstants.SAMPLE_RATE, 2, AcousticConstants.DSP_BLOCK_SIZE);
        mixer = new SoftwareMixer(mgr, queue, device, new MixSnapshotManager());
    }

    @Test
    void renderBlockAllocatesLessThanThresholdPerBlock() {
        MemoryMXBean memBean = ManagementFactory.getMemoryMXBean();

        // Warm up - allow JIT and class loading to complete
        for (int i = 0; i < WARMUP_BLOCKS; i++) {
            mixer.renderBlock();
        }

        // Force GC to get a clean baseline
        System.gc();
        Thread.yield();

        long heapBefore = memBean.getHeapMemoryUsage().getUsed();

        for (int i = 0; i < MEASURED_BLOCKS; i++) {
            mixer.renderBlock();
        }

        long heapAfter = memBean.getHeapMemoryUsage().getUsed();
        long allocated = heapAfter - heapBefore;
        long perBlock = allocated / MEASURED_BLOCKS;

        assertThat(perBlock)
            .as("Heap allocated per renderBlock() call after warmup: %d bytes. " +
                "Threshold: %d bytes. A new allocation was introduced in the render path.",
                perBlock, MAX_BYTES_PER_BLOCK)
            .isLessThan(MAX_BYTES_PER_BLOCK);
    }
}
