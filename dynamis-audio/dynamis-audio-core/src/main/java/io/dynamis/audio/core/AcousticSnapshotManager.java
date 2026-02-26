package io.dynamis.audio.core;

import io.dynamis.audio.api.AcousticWorldSnapshot;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * Double-buffered snapshot manager.
 *
 * Bridges the game thread (writer) and audio thread (reader) with zero contention.
 * Two AcousticWorldSnapshotImpl instances are swapped via a VarHandle acquire/release
 * fence - no locks, no blocking, no allocation on either thread's hot path.
 *
 * PROTOCOL:
 *   Game thread:
 *     1. AcousticWorldSnapshotImpl back = acquireBackBuffer()
 *     2. Mutate back: putPortal(), putRoom(), putMaterial(), setVersionAndTime()
 *     3. publish()  <- memory fence; back becomes the new front
 *
 *   Audio thread:
 *     1. AcousticWorldSnapshot snap = acquireLatest()
 *     2. Read snap for the duration of one DSP block
 *     3. Never holds a reference across DSP blocks (snapshot may be re-used as back buffer)
 *
 * THREAD SAFETY:
 *   Game thread owns back buffer mutation exclusively.
 *   Audio thread reads front buffer exclusively.
 *   VarHandle acquire/release provides the required memory ordering.
 *   Even during a 100ms game thread spike, the audio thread keeps playing
 *   from the last published snapshot without stalling.
 */
public final class AcousticSnapshotManager {

    private static final VarHandle FRONT_IDX;

    static {
        try {
            FRONT_IDX = MethodHandles.lookup()
                .findVarHandle(AcousticSnapshotManager.class, "frontIndex", int.class);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final AcousticWorldSnapshotImpl[] buffers = {
        new AcousticWorldSnapshotImpl(),
        new AcousticWorldSnapshotImpl()
    };

    @SuppressWarnings("FieldMayBeFinal")
    private volatile int frontIndex = 0;

    private long nextVersion = 1L;

    /**
     * Game thread: returns the back buffer for mutation this frame.
     * The returned instance must not be published to any other thread
     * until publish() is called.
     */
    public AcousticWorldSnapshotImpl acquireBackBuffer() {
        int front = (int) FRONT_IDX.getAcquire(this);
        return buffers[1 - front];
    }

    /**
     * Game thread: publishes the back buffer as the new front.
     *
     * Call AFTER all mutations to the back buffer are complete, including
     * setVersionAndTime(). The setRelease fence ensures all prior writes
     * are visible to the audio thread after it reads frontIndex with getAcquire.
     *
     * Stamps the back buffer with a monotonically increasing version before swapping.
     */
    public void publish() {
        int back = 1 - (int) FRONT_IDX.getAcquire(this);
        buffers[back].setVersionAndTime(nextVersion++, System.nanoTime());
        FRONT_IDX.setRelease(this, back);
    }

    /**
     * Audio thread: returns the latest stable published snapshot.
     *
     * Never blocks. Returns the most recently published front buffer.
     * The caller must not hold this reference across DSP block boundaries -
     * the game thread may begin mutating this buffer on the next cycle.
     *
     * Safe to call from any thread for inspection/debugging.
     */
    public AcousticWorldSnapshot acquireLatest() {
        return buffers[(int) FRONT_IDX.getAcquire(this)];
    }

    /**
     * Returns the version of the currently published front snapshot.
     * Useful for staleness detection without acquiring the full snapshot.
     */
    public long currentVersion() {
        return buffers[(int) FRONT_IDX.getAcquire(this)].version();
    }
}
