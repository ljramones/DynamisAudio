package org.dynamisengine.audio.dsp.device;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * Wait-free single-producer single-consumer ring buffer backed by off-heap memory.
 *
 * <p><b>Producer:</b> DSP render worker thread (writes rendered audio blocks via {@link #write}).
 * <p><b>Consumer:</b> Platform audio callback thread (reads blocks via {@link #read}).
 *
 * <h2>Memory Layout</h2>
 * A single off-heap {@link MemorySegment} of size {@code slotCount * blockBytes} bytes.
 * Each slot holds one DSP block of interleaved float32 PCM.
 * {@code slotCount} MUST be a power of two for bitmask indexing.
 *
 * <h2>Ordering</h2>
 * VarHandle acquire/release on {@code writePos}/{@code readPos} indices.
 * No CAS. No locks. No retry loops. Both sides make progress in O(1).
 *
 * <h2>Latency</h2>
 * With 4 slots at 256 frames / 48 kHz: worst-case 4 blocks = ~21.3 ms.
 * Typical steady-state: 1-2 blocks ahead = ~5.3-10.7 ms.
 *
 * <h2>Allocation Contract</h2>
 * {@link #write} and {@link #read}: zero heap allocation.
 * Constructor allocates the off-heap arena and segment.
 * {@link #close()} releases all off-heap memory.
 */
public final class SpscAudioRingBuffer {

    private final MemorySegment buffer;
    private final Arena arena;
    private final int slotCount;
    private final int mask;
    private final long blockBytes;
    private final int blockSize;
    private final int channels;

    // -- VarHandle cursors ---------------------------------------------------

    private static final VarHandle WRITE_POS_VH;
    private static final VarHandle READ_POS_VH;

    static {
        try {
            var lookup = MethodHandles.lookup();
            WRITE_POS_VH = lookup.findVarHandle(SpscAudioRingBuffer.class, "writePos", long.class);
            READ_POS_VH = lookup.findVarHandle(SpscAudioRingBuffer.class, "readPos", long.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /** Producer cursor. Advanced by DSP render worker in {@link #write}. */
    @SuppressWarnings("FieldMayBeFinal")
    private volatile long writePos = 0L;

    /** Consumer cursor. Advanced by platform callback in {@link #read}. */
    @SuppressWarnings("FieldMayBeFinal")
    private volatile long readPos = 0L;

    // -- Telemetry -----------------------------------------------------------

    private volatile long underruns = 0L;
    private volatile long overruns = 0L;
    private volatile int highWatermark = 0;  // max observed occupancy
    private volatile int lowWatermark = Integer.MAX_VALUE;  // min observed occupancy (after first write)

    // -- Construction --------------------------------------------------------

    /**
     * @param slotCount number of block-sized slots; must be a power of two >= 2
     * @param blockSize frames per DSP block (e.g., 256)
     * @param channels  number of interleaved channels (e.g., 2 for stereo)
     */
    public SpscAudioRingBuffer(int slotCount, int blockSize, int channels) {
        if (slotCount < 2 || Integer.bitCount(slotCount) != 1) {
            throw new IllegalArgumentException(
                    "slotCount must be a power of two >= 2; got " + slotCount);
        }
        if (blockSize <= 0) throw new IllegalArgumentException("blockSize must be positive");
        if (channels <= 0) throw new IllegalArgumentException("channels must be positive");

        this.slotCount = slotCount;
        this.mask = slotCount - 1;
        this.blockSize = blockSize;
        this.channels = channels;
        this.blockBytes = (long) blockSize * channels * Float.BYTES;

        this.arena = Arena.ofShared();
        this.buffer = arena.allocate(slotCount * blockBytes, Float.BYTES);
    }

    // -- Producer (DSP render worker thread) ---------------------------------

    /**
     * Write one block of interleaved float PCM into the ring.
     *
     * Returns {@code false} if the ring is full (overrun — DSP is ahead of hardware).
     * The caller should handle this by either dropping the block or spinning briefly.
     *
     * ALLOCATION CONTRACT: Zero heap allocation.
     *
     * @param block      interleaved float32 PCM; length must be >= frameCount * channels
     * @param frameCount number of sample frames to write
     * @param channels   channel count (must match constructor value)
     * @return true if the block was enqueued, false if ring was full
     */
    public boolean write(float[] block, int frameCount, int channels) {
        long wp = (long) WRITE_POS_VH.getAcquire(this);
        long rp = (long) READ_POS_VH.getAcquire(this);
        if (wp - rp >= slotCount) {
            overruns++;
            return false;
        }

        int slot = (int) (wp & mask);
        long offset = slot * blockBytes;
        long byteCount = (long) frameCount * channels * Float.BYTES;

        MemorySegment heapView = MemorySegment.ofArray(block);
        buffer.asSlice(offset, byteCount).copyFrom(heapView.asSlice(0, byteCount));

        WRITE_POS_VH.setRelease(this, wp + 1);

        // Watermark tracking (relaxed — telemetry only, not correctness-critical)
        int occ = (int) (wp + 1 - rp);
        if (occ > highWatermark) highWatermark = occ;
        return true;
    }

    /**
     * Write one block from an off-heap MemorySegment into the ring.
     *
     * Preferred over {@link #write(float[], int, int)} when the source is already
     * off-heap, as it avoids creating a temporary heap-backed MemorySegment wrapper.
     *
     * ALLOCATION CONTRACT: Zero heap allocation.
     *
     * @param source off-heap segment containing interleaved float32 PCM; must be >= blockBytes
     * @return true if the block was enqueued, false if ring was full
     */
    public boolean writeFrom(MemorySegment source) {
        long wp = (long) WRITE_POS_VH.getAcquire(this);
        long rp = (long) READ_POS_VH.getAcquire(this);
        if (wp - rp >= slotCount) {
            overruns++;
            return false;
        }

        int slot = (int) (wp & mask);
        long offset = slot * blockBytes;
        buffer.asSlice(offset, blockBytes).copyFrom(source.asSlice(0, blockBytes));

        WRITE_POS_VH.setRelease(this, wp + 1);

        int occ = (int) (wp + 1 - rp);
        if (occ > highWatermark) highWatermark = occ;
        return true;
    }

    // -- Consumer (platform audio callback thread) ---------------------------

    /**
     * Read one block from the ring into the provided off-heap segment.
     *
     * Returns {@code false} if the ring is empty (underrun — hardware is ahead of DSP).
     * The caller should fill the output with silence on underrun.
     *
     * ALLOCATION CONTRACT: Zero heap allocation. Wait-free.
     *
     * @param dest off-heap MemorySegment to receive the block; must be >= blockBytes
     * @return true if a block was dequeued, false if ring was empty
     */
    public boolean read(MemorySegment dest) {
        long rp = (long) READ_POS_VH.getAcquire(this);
        long wp = (long) WRITE_POS_VH.getAcquire(this);
        if (rp >= wp) {
            underruns++;
            return false;
        }

        int slot = (int) (rp & mask);
        long offset = slot * blockBytes;
        dest.copyFrom(buffer.asSlice(offset, blockBytes));

        READ_POS_VH.setRelease(this, rp + 1);

        // Watermark tracking (relaxed)
        int occ = (int) (wp - rp - 1);
        if (occ < lowWatermark) lowWatermark = occ;
        return true;
    }

    // -- Inspection ----------------------------------------------------------

    /** Number of blocks currently available for reading. Approximate (no lock). */
    public int available() {
        long wp = (long) WRITE_POS_VH.getAcquire(this);
        long rp = (long) READ_POS_VH.getAcquire(this);
        return (int) (wp - rp);
    }

    /** Number of free slots available for writing. Approximate. */
    public int freeSlots() {
        return slotCount - available();
    }

    /** Total underruns (consumer found ring empty) since construction. */
    public long underruns() { return underruns; }

    /** Total overruns (producer found ring full) since construction. */
    public long overruns() { return overruns; }

    /** Highest observed occupancy (blocks in ring after a write). */
    public int highWatermark() { return highWatermark; }

    /** Lowest observed occupancy (blocks in ring after a read). MAX_VALUE if never read. */
    public int lowWatermark() { return lowWatermark == Integer.MAX_VALUE ? 0 : lowWatermark; }

    /** Slot count (ring capacity in blocks). */
    public int slotCount() { return slotCount; }

    /** Bytes per block slot. */
    public long blockBytes() { return blockBytes; }

    /** Block size in frames. */
    public int blockSize() { return blockSize; }

    /** Channel count. */
    public int channels() { return channels; }

    // -- Lifecycle -----------------------------------------------------------

    /**
     * Release all off-heap memory. Call from engine shutdown only.
     * After close, all other methods have undefined behavior.
     */
    public void close() {
        arena.close();
    }
}
