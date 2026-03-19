package org.dynamisengine.audio.test;

import org.dynamisengine.audio.dsp.device.SpscAudioRingBuffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import static org.assertj.core.api.Assertions.*;

class SpscAudioRingBufferTest {

    private SpscAudioRingBuffer ring;

    @AfterEach
    void tearDown() {
        if (ring != null) ring.close();
    }

    // -- Construction --------------------------------------------------------

    @Test
    void rejectsNonPowerOfTwo() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SpscAudioRingBuffer(3, 256, 2));
    }

    @Test
    void rejectsSlotCountLessThanTwo() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SpscAudioRingBuffer(1, 256, 2));
    }

    @Test
    void rejectsZeroBlockSize() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SpscAudioRingBuffer(4, 0, 2));
    }

    @Test
    void rejectsZeroChannels() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SpscAudioRingBuffer(4, 256, 0));
    }

    @Test
    void constructsSuccessfully() {
        ring = new SpscAudioRingBuffer(4, 256, 2);
        assertThat(ring.slotCount()).isEqualTo(4);
        assertThat(ring.blockSize()).isEqualTo(256);
        assertThat(ring.channels()).isEqualTo(2);
        assertThat(ring.blockBytes()).isEqualTo(256L * 2 * Float.BYTES);
        assertThat(ring.available()).isZero();
        assertThat(ring.freeSlots()).isEqualTo(4);
    }

    // -- Write / Read --------------------------------------------------------

    @Test
    void writeAndReadSingleBlock() {
        ring = new SpscAudioRingBuffer(4, 4, 1); // 4 frames, 1 channel
        float[] block = {0.1f, 0.2f, 0.3f, 0.4f};

        assertThat(ring.write(block, 4, 1)).isTrue();
        assertThat(ring.available()).isEqualTo(1);

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment dest = arena.allocate(ring.blockBytes(), Float.BYTES);
            assertThat(ring.read(dest)).isTrue();
            assertThat(ring.available()).isZero();

            // Verify content
            for (int i = 0; i < 4; i++) {
                assertThat(dest.get(ValueLayout.JAVA_FLOAT, (long) i * Float.BYTES))
                        .isEqualTo(block[i]);
            }
        }
    }

    @Test
    void fillAndDrainEntireRing() {
        ring = new SpscAudioRingBuffer(4, 2, 1); // 4 slots, 2 frames, 1 ch
        float[][] blocks = {
                {1.0f, 2.0f}, {3.0f, 4.0f}, {5.0f, 6.0f}, {7.0f, 8.0f}
        };

        for (float[] b : blocks) {
            assertThat(ring.write(b, 2, 1)).isTrue();
        }
        assertThat(ring.available()).isEqualTo(4);
        assertThat(ring.freeSlots()).isZero();

        // Ring full — next write should fail
        assertThat(ring.write(new float[]{9.0f, 10.0f}, 2, 1)).isFalse();
        assertThat(ring.overruns()).isEqualTo(1);

        // Drain all
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment dest = arena.allocate(ring.blockBytes(), Float.BYTES);
            for (int i = 0; i < 4; i++) {
                assertThat(ring.read(dest)).isTrue();
                assertThat(dest.get(ValueLayout.JAVA_FLOAT, 0)).isEqualTo(blocks[i][0]);
            }
            assertThat(ring.available()).isZero();
        }
    }

    @Test
    void readFromEmptyRingReportsUnderrun() {
        ring = new SpscAudioRingBuffer(2, 2, 1);

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment dest = arena.allocate(ring.blockBytes(), Float.BYTES);
            assertThat(ring.read(dest)).isFalse();
            assertThat(ring.underruns()).isEqualTo(1);
        }
    }

    @Test
    void wrapAroundCorrectly() {
        ring = new SpscAudioRingBuffer(2, 2, 1); // 2 slots

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment dest = arena.allocate(ring.blockBytes(), Float.BYTES);

            // Write 2, read 2, write 2 more (wraps around slot indices)
            for (int cycle = 0; cycle < 4; cycle++) {
                float[] block = {(float) cycle, (float) (cycle + 0.5)};
                assertThat(ring.write(block, 2, 1)).isTrue();

                assertThat(ring.read(dest)).isTrue();
                assertThat(dest.get(ValueLayout.JAVA_FLOAT, 0)).isEqualTo((float) cycle);
                assertThat(dest.get(ValueLayout.JAVA_FLOAT, Float.BYTES))
                        .isEqualTo((float) (cycle + 0.5));
            }
        }
    }

    @Test
    void stereoBlockRoundTrip() {
        ring = new SpscAudioRingBuffer(4, 256, 2); // standard game audio config
        float[] block = new float[256 * 2];
        for (int i = 0; i < block.length; i++) {
            block[i] = (float) i / block.length;
        }

        assertThat(ring.write(block, 256, 2)).isTrue();

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment dest = arena.allocate(ring.blockBytes(), Float.BYTES);
            assertThat(ring.read(dest)).isTrue();

            // Verify first and last samples
            assertThat(dest.get(ValueLayout.JAVA_FLOAT, 0)).isEqualTo(block[0]);
            assertThat(dest.get(ValueLayout.JAVA_FLOAT, (long) (block.length - 1) * Float.BYTES))
                    .isEqualTo(block[block.length - 1]);
        }
    }

    @Test
    void telemetryCountersAccumulate() {
        ring = new SpscAudioRingBuffer(2, 2, 1);

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment dest = arena.allocate(ring.blockBytes(), Float.BYTES);

            // 3 underruns
            ring.read(dest);
            ring.read(dest);
            ring.read(dest);
            assertThat(ring.underruns()).isEqualTo(3);

            // Fill ring, then 2 overruns
            ring.write(new float[]{1, 2}, 2, 1);
            ring.write(new float[]{3, 4}, 2, 1);
            ring.write(new float[]{5, 6}, 2, 1);
            ring.write(new float[]{7, 8}, 2, 1);
            assertThat(ring.overruns()).isEqualTo(2);
        }
    }

    // -- Concurrent stress test (SPSC) ---------------------------------------

    @Test
    void concurrentProducerConsumer() throws InterruptedException {
        ring = new SpscAudioRingBuffer(8, 4, 1); // 8 slots, 4 frames
        int totalBlocks = 10_000;
        long[] producerSum = {0};
        long[] consumerSum = {0};

        Thread producer = Thread.ofPlatform().start(() -> {
            for (int i = 0; i < totalBlocks; i++) {
                float[] block = {(float) i, 0, 0, 0};
                while (!ring.write(block, 4, 1)) {
                    Thread.onSpinWait();
                }
                producerSum[0] += i;
            }
        });

        Thread consumer = Thread.ofPlatform().start(() -> {
            try (Arena a = Arena.ofConfined()) {
                MemorySegment dest = a.allocate(ring.blockBytes(), Float.BYTES);
                for (int i = 0; i < totalBlocks; i++) {
                    while (!ring.read(dest)) {
                        Thread.onSpinWait();
                    }
                    consumerSum[0] += (long) dest.get(ValueLayout.JAVA_FLOAT, 0);
                }
            }
        });

        producer.join(5000);
        consumer.join(5000);

        assertThat(consumerSum[0]).isEqualTo(producerSum[0]);
        // Underruns/overruns may occur under scheduling pressure — the key
        // correctness property is that all produced data was consumed intact.
    }
}
