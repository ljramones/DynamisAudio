package io.dynamis.audio.test;

import io.dynamis.audio.api.*;
import io.dynamis.audio.core.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AcousticEventQueueImplTest {

    private AcousticEventQueueImpl queue;
    private AcousticEventBuffer buffer;

    @BeforeEach
    void setUp() {
        queue = new AcousticEventQueueImpl(16); // small capacity for overflow testing
        buffer = new AcousticEventBuffer(16);
    }

    @Test
    void defaultConstructorUsesAcousticConstantsCapacity() {
        AcousticEventQueueImpl q = new AcousticEventQueueImpl();
        assertThat(q.capacity()).isEqualTo(AcousticConstants.EVENT_RING_CAPACITY);
    }

    @Test
    void nonPowerOfTwoCapacityThrows() {
        assertThatThrownBy(() -> new AcousticEventQueueImpl(7))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void capacityOneTThrows() {
        assertThatThrownBy(() -> new AcousticEventQueueImpl(1))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void drainOnEmptyQueueReturnsZero() {
        int count = queue.drainTo(buffer);
        assertThat(count).isZero();
        assertThat(buffer.count()).isZero();
    }

    @Test
    void enqueueAndDrainSingleEvent() {
        var event = new AcousticEvent.PortalStateChanged(1000L, 42L, 0.5f);
        queue.enqueue(event);
        int count = queue.drainTo(buffer);
        assertThat(count).isEqualTo(1);
        assertThat(buffer.get(0)).isEqualTo(event);
    }

    @Test
    void drainPreservesEventOrder() {
        var e1 = new AcousticEvent.GeometryDestroyedEvent(1000L, 1L);
        var e2 = new AcousticEvent.GeometryDestroyedEvent(2000L, 2L);
        var e3 = new AcousticEvent.GeometryDestroyedEvent(3000L, 3L);
        queue.enqueue(e1);
        queue.enqueue(e2);
        queue.enqueue(e3);
        queue.drainTo(buffer);
        assertThat(buffer.get(0)).isEqualTo(e1);
        assertThat(buffer.get(1)).isEqualTo(e2);
        assertThat(buffer.get(2)).isEqualTo(e3);
    }

    @Test
    void drainClearsQueueSoPendingCountIsZero() {
        queue.enqueue(new AcousticEvent.PortalStateChanged(0L, 1L, 1.0f));
        queue.drainTo(buffer);
        assertThat(queue.pendingCount()).isZero();
    }

    @Test
    void portalStateChangedEventsAreCoalesced() {
        long portalId = 7L;
        var first = new AcousticEvent.PortalStateChanged(1000L, portalId, 0.2f);
        var second = new AcousticEvent.PortalStateChanged(2000L, portalId, 0.8f);
        queue.enqueue(first);
        queue.enqueue(second); // should replace first in-place
        int count = queue.drainTo(buffer);
        assertThat(count).isEqualTo(1);
        // Only the latest aperture should survive
        assertThat(((AcousticEvent.PortalStateChanged) buffer.get(0)).aperture())
            .isEqualTo(0.8f);
    }

    @Test
    void differentPortalIdsAreNotCoalesced() {
        queue.enqueue(new AcousticEvent.PortalStateChanged(0L, 1L, 0.5f));
        queue.enqueue(new AcousticEvent.PortalStateChanged(0L, 2L, 0.5f));
        int count = queue.drainTo(buffer);
        assertThat(count).isEqualTo(2);
    }

    @Test
    void materialOverrideChangedIsNotCoalesced() {
        var e1 = new AcousticEvent.MaterialOverrideChanged(0L, 10L, 1);
        var e2 = new AcousticEvent.MaterialOverrideChanged(0L, 10L, 2);
        queue.enqueue(e1);
        queue.enqueue(e2);
        int count = queue.drainTo(buffer);
        assertThat(count).isEqualTo(2);
    }

    @Test
    void overflowIncrementsDroppedCount() {
        // Fill ring completely
        for (int i = 0; i < 16; i++) {
            queue.enqueue(new AcousticEvent.GeometryDestroyedEvent((long) i, (long) i));
        }
        assertThat(queue.droppedEventCount()).isZero();
        // One more should overflow
        queue.enqueue(new AcousticEvent.GeometryDestroyedEvent(999L, 999L));
        assertThat(queue.droppedEventCount()).isEqualTo(1L);
    }

    @Test
    void drainAfterOverflowReturnsCapacityEvents() {
        for (int i = 0; i < 20; i++) {
            queue.enqueue(new AcousticEvent.GeometryDestroyedEvent((long) i, (long) i));
        }
        int count = queue.drainTo(buffer);
        assertThat(count).isEqualTo(16); // buffer and ring both capacity 16
    }

    @Test
    void enqueueNullThrows() {
        assertThatThrownBy(() -> queue.enqueue(null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void pendingCountReflectsEnqueuedEvents() {
        assertThat(queue.pendingCount()).isZero();
        queue.enqueue(new AcousticEvent.PortalStateChanged(0L, 1L, 1.0f));
        queue.enqueue(new AcousticEvent.PortalStateChanged(0L, 2L, 1.0f));
        assertThat(queue.pendingCount()).isEqualTo(2);
    }

    @Test
    void multipleRoundTripDrainCycles() {
        // Enqueue, drain, enqueue again - validates ring wraps correctly
        for (int cycle = 0; cycle < 4; cycle++) {
            for (int i = 0; i < 4; i++) {
                queue.enqueue(new AcousticEvent.GeometryDestroyedEvent(
                    (long) (cycle * 10 + i), (long) i));
            }
            int count = queue.drainTo(buffer);
            assertThat(count).isEqualTo(4);
        }
        assertThat(queue.droppedEventCount()).isZero();
    }
}
